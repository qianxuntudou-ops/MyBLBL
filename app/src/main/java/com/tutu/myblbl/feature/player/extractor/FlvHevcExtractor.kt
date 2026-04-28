package com.tutu.myblbl.feature.player.extractor

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.AvcConfig
import androidx.media3.extractor.HevcConfig

/**
 * FLV extractor that supports both AVC (H.264) and HEVC (H.265) video codecs.
 *
 * Standard ExoPlayer [androidx.media3.extractor.flv.FlvExtractor] only supports AVC (codec ID 7)
 * and throws [UnsupportedFormatException] for HEVC (codec ID 12). This extractor handles both
 * by parsing the respective decoder configuration records (AVCDecoderConfigurationRecord and
 * HEVCDecoderConfigurationRecord) and outputting the correct MIME type.
 */
class FlvHevcExtractor : Extractor {

    companion object {
        private const val TAG_TYPE_AUDIO = 8
        private const val TAG_TYPE_VIDEO = 9

        private const val CODEC_AVC = 7
        private const val CODEC_HEVC = 12

        private const val PACKET_SEQ_START = 0
        private const val PACKET_NALU = 1

        private const val SOUND_FORMAT_AAC = 10
        private const val AAC_PACKET_SEQ_START = 0
        private const val AAC_PACKET_RAW = 1

        private const val STATE_HEADER = 0
        private const val STATE_TAG_HEADER = 1
        private const val STATE_TAG_DATA = 2
        private const val STATE_SKIP = 3

        private const val FLV_HEADER_SIZE = 9
        private const val TAG_HEADER_SIZE = 11
        private const val PREV_TAG_SIZE = 4

        private val NAL_START_CODE = byteArrayOf(0, 0, 0, 1)
        private val AAC_SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
        )
    }

    private var state = STATE_HEADER
    private var tagType = 0
    private var tagDataSize = 0
    private var tagTimestampUs = 0L
    private var skipBytes = 0

    private lateinit var output: ExtractorOutput
    private var videoTrack: TrackOutput? = null
    private var audioTrack: TrackOutput? = null
    private var tracksEnded = false
    private var nalLengthSize = 4

    private val headerBuf = ParsableByteArray(FLV_HEADER_SIZE)
    private val tagHeaderBuf = ParsableByteArray(TAG_HEADER_SIZE)

    override fun sniff(input: ExtractorInput): Boolean {
        val sig = ByteArray(3)
        input.peekFully(sig, 0, 3)
        return sig[0] == 'F'.code.toByte() &&
            sig[1] == 'L'.code.toByte() &&
            sig[2] == 'V'.code.toByte()
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        state = STATE_HEADER
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        while (true) {
            when (state) {
                STATE_HEADER -> {
                    input.readFully(headerBuf.data, 0, FLV_HEADER_SIZE)
                    headerBuf.setPosition(5)
                    val dataOffset = headerBuf.readUnsignedIntToInt()
                    skipBytes = dataOffset - FLV_HEADER_SIZE + PREV_TAG_SIZE
                    state = STATE_SKIP
                }

                STATE_SKIP -> {
                    input.skipFully(skipBytes)
                    skipBytes = 0
                    state = STATE_TAG_HEADER
                }

                STATE_TAG_HEADER -> {
                    input.readFully(tagHeaderBuf.data, 0, TAG_HEADER_SIZE)
                    tagHeaderBuf.setPosition(0)
                    tagType = tagHeaderBuf.readUnsignedByte()
                    tagDataSize = tagHeaderBuf.readUnsignedInt24()
                    val tsLower = tagHeaderBuf.readUnsignedInt24()
                    val tsUpper = tagHeaderBuf.readUnsignedByte()
                    tagHeaderBuf.skipBytes(3)
                    tagTimestampUs = ((tsUpper.toLong() shl 24) or tsLower.toLong()) * 1000L
                    if (tagDataSize > 0) {
                        state = STATE_TAG_DATA
                    } else {
                        skipBytes = PREV_TAG_SIZE
                        state = STATE_SKIP
                    }
                }

                STATE_TAG_DATA -> {
                    when (tagType) {
                        TAG_TYPE_VIDEO -> readVideoTag(input)
                        TAG_TYPE_AUDIO -> readAudioTag(input)
                        else -> input.skipFully(tagDataSize)
                    }
                    skipBytes = PREV_TAG_SIZE
                    state = STATE_SKIP
                }
            }
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        state = STATE_HEADER
    }

    override fun release() = Unit

    // ---- Video ----

    private fun readVideoTag(input: ExtractorInput) {
        val headerSize = minOf(5, tagDataSize)
        val hdr = ParsableByteArray(headerSize)
        input.readFully(hdr.data, 0, headerSize)
        hdr.setPosition(0)

        val firstByte = hdr.readUnsignedByte()
        val codecId = firstByte and 0x0F
        val frameType = (firstByte shr 4) and 0x0F
        val packetType = if (headerSize > 1) hdr.readUnsignedByte() else 0
        val payloadSize = tagDataSize - headerSize

        when (codecId) {
            CODEC_AVC, CODEC_HEVC -> handleVideoPacket(input, codecId, packetType, frameType, payloadSize)
            else -> input.skipFully(payloadSize)
        }
    }

    private fun handleVideoPacket(
        input: ExtractorInput,
        codecId: Int,
        packetType: Int,
        frameType: Int,
        payloadSize: Int
    ) {
        if (payloadSize <= 0) return

        when (packetType) {
            PACKET_SEQ_START -> {
                val data = ParsableByteArray(payloadSize)
                input.readFully(data.data, 0, payloadSize)
                when (codecId) {
                    CODEC_AVC -> parseAvcConfig(data)
                    CODEC_HEVC -> parseHevcConfig(data)
                }
            }

            PACKET_NALU -> {
                maybeEndTracks()
                val track = videoTrack()
                val rawBuf = ByteArray(payloadSize)
                input.readFully(rawBuf, 0, payloadSize)
                val annexB = convertToAnnexB(rawBuf, payloadSize)
                if (annexB.isNotEmpty()) {
                    track.sampleData(ParsableByteArray(annexB), annexB.size)
                    val flags = if (frameType == 1) C.BUFFER_FLAG_KEY_FRAME else 0
                    track.sampleMetadata(tagTimestampUs, flags, annexB.size, 0, null)
                }
            }

            else -> input.skipFully(payloadSize)
        }
    }

    private fun parseAvcConfig(data: ParsableByteArray) {
        val config = AvcConfig.parse(data)
        nalLengthSize = config.nalUnitLengthFieldLength
        videoTrack().format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setInitializationData(config.initializationData)
                .setWidth(config.width)
                .setHeight(config.height)
                .build()
        )
    }

    private fun parseHevcConfig(data: ParsableByteArray) {
        val config = HevcConfig.parse(data)
        nalLengthSize = config.nalUnitLengthFieldLength
        videoTrack().format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setInitializationData(config.initializationData)
                .setWidth(config.width)
                .setHeight(config.height)
                .build()
        )
    }

    private fun convertToAnnexB(raw: ByteArray, rawLength: Int): ByteArray {
        var pos = 0
        var outLen = 0
        while (pos + nalLengthSize <= rawLength) {
            var nalLength = 0
            for (i in 0 until nalLengthSize) {
                nalLength = (nalLength shl 8) or (raw[pos + i].toInt() and 0xFF)
            }
            pos += nalLengthSize
            if (nalLength <= 0 || pos + nalLength > rawLength) break
            outLen += 4 + nalLength
            pos += nalLength
        }
        if (outLen == 0) return ByteArray(0)
        val out = ByteArray(outLen)
        pos = 0
        var outPos = 0
        while (pos + nalLengthSize <= rawLength) {
            var nalLength = 0
            for (i in 0 until nalLengthSize) {
                nalLength = (nalLength shl 8) or (raw[pos + i].toInt() and 0xFF)
            }
            pos += nalLengthSize
            if (nalLength <= 0 || pos + nalLength > rawLength) break
            System.arraycopy(NAL_START_CODE, 0, out, outPos, 4)
            outPos += 4
            System.arraycopy(raw, pos, out, outPos, nalLength)
            outPos += nalLength
            pos += nalLength
        }
        return out
    }

    // ---- Audio ----

    private fun readAudioTag(input: ExtractorInput) {
        if (tagDataSize < 2) {
            input.skipFully(tagDataSize)
            return
        }
        val hdr = ParsableByteArray(2)
        input.readFully(hdr.data, 0, 2)
        hdr.setPosition(0)

        val firstByte = hdr.readUnsignedByte()
        val soundFormat = (firstByte shr 4) and 0x0F
        val payloadSize = tagDataSize - 1

        if (soundFormat == SOUND_FORMAT_AAC) {
            val aacPacketType = hdr.readUnsignedByte()
            val aacPayload = tagDataSize - 2
            when {
                aacPacketType == AAC_PACKET_SEQ_START && aacPayload > 0 -> {
                    val data = ParsableByteArray(aacPayload)
                    input.readFully(data.data, 0, aacPayload)
                    parseAacConfig(data)
                }

                aacPacketType == AAC_PACKET_RAW && aacPayload > 0 -> {
                    maybeEndTracks()
                    val track = audioTrack()
                    val audioBuf = ParsableByteArray(aacPayload)
                    input.readFully(audioBuf.data, 0, aacPayload)
                    track.sampleData(audioBuf, aacPayload)
                    track.sampleMetadata(tagTimestampUs, C.BUFFER_FLAG_KEY_FRAME, aacPayload, 0, null)
                }

                else -> input.skipFully(aacPayload)
            }
        } else {
            input.skipFully(payloadSize)
        }
    }

    private fun parseAacConfig(data: ParsableByteArray) {
        data.setPosition(0)
        val b0 = data.readUnsignedByte()
        val b1 = data.readUnsignedByte()
        val freqIndex = ((b0 and 0x07) shl 1) or ((b1 shr 7) and 0x01)
        val channelConfig = (b1 shr 3) and 0x0F
        val sampleRate = AAC_SAMPLE_RATES.getOrElse(freqIndex) { 44100 }
        audioTrack().format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(sampleRate)
                .setChannelCount(maxOf(channelConfig, 1))
                .setInitializationData(listOf(byteArrayOf(b0.toByte(), b1.toByte())))
                .build()
        )
    }

    // ---- Track helpers ----

    private fun videoTrack(): TrackOutput =
        videoTrack ?: output.track(0, C.TRACK_TYPE_VIDEO).also { videoTrack = it }

    private fun audioTrack(): TrackOutput =
        audioTrack ?: output.track(1, C.TRACK_TYPE_AUDIO).also { audioTrack = it }

    private fun maybeEndTracks() {
        if (tracksEnded) return
        output.endTracks()
        output.seekMap(object : SeekMap {
            override fun isSeekable() = false
            override fun getDurationUs() = C.TIME_UNSET
            override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(0, 0))
        })
        tracksEnded = true
    }
}
