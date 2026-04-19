package com.tutu.myblbl.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import java.io.ByteArrayInputStream

@OptIn(UnstableApi::class)
object VideoPlayerDashManifestBuilder {

    fun buildManifest(route: DashRoute): DashManifest {
        val start = System.currentTimeMillis()

        val durationS = route.durationMs / 1000.0
        val minBufferS = route.minBufferTimeMs / 1000.0
        val durationAttr = formatMpdDuration(route.durationMs)

        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
            append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
            append("type=\"static\" minBufferTime=\"${formatMpdDuration(route.minBufferTimeMs)}\"")
            if (durationAttr.isNotEmpty()) {
                append(" mediaPresentationDuration=\"").append(durationAttr).append("\"")
            }
            append(">\n")
            append("  <Period start=\"${formatMpdDuration(0L)}\"")
            if (durationAttr.isNotEmpty()) {
                append(" duration=\"").append(durationAttr).append("\"")
            }
            append(">\n")

            appendAdaptationSet(
                contentType = "video",
                mimeType = route.videoRepresentation.mimeType,
                representationId = "video_${route.videoRepresentation.id}",
                bandwidth = route.videoRepresentation.bandwidth,
                codecs = route.videoRepresentation.codecs,
                width = route.videoRepresentation.width,
                height = route.videoRepresentation.height,
                frameRate = route.videoRepresentation.frameRate,
                baseUrl = route.videoRepresentation.baseUrl,
                segmentBase = route.videoRepresentation.segmentBase
            )

            route.audioRepresentation?.let { audio ->
                appendAdaptationSet(
                    contentType = "audio",
                    mimeType = audio.mimeType,
                    representationId = "audio_${audio.id}",
                    bandwidth = audio.bandwidth,
                    codecs = audio.codecs,
                    width = 0,
                    height = 0,
                    frameRate = "",
                    baseUrl = audio.baseUrl,
                    segmentBase = audio.segmentBase
                )
            }

            append("  </Period>\n")
            append("</MPD>\n")
        }

        val manifest = DashManifestParser()
            .parse(Uri.parse("dash://local"), ByteArrayInputStream(xml.toByteArray()))

        val elapsed = System.currentTimeMillis() - start

        return manifest
    }

    private fun StringBuilder.appendAdaptationSet(
        contentType: String,
        mimeType: String,
        representationId: String,
        bandwidth: Long,
        codecs: String,
        width: Int,
        height: Int,
        frameRate: String,
        baseUrl: String,
        segmentBase: DashSegmentBase?
    ) {
        append("    <AdaptationSet contentType=\"").append(xmlEscapeAttr(contentType)).append("\"")
        append(" mimeType=\"").append(xmlEscapeAttr(mimeType)).append("\">\n")

        append("      <Representation id=\"").append(xmlEscapeAttr(representationId)).append("\"")
        if (bandwidth > 0L) {
            append(" bandwidth=\"").append(bandwidth).append("\"")
        }
        codecs.trim().takeIf { it.isNotBlank() }?.let {
            append(" codecs=\"").append(xmlEscapeAttr(it)).append("\"")
        }
        if (width > 0) {
            append(" width=\"").append(width).append("\"")
        }
        if (height > 0) {
            append(" height=\"").append(height).append("\"")
        }
        frameRate.trim().takeIf { it.isNotBlank() }?.let {
            append(" frameRate=\"").append(xmlEscapeAttr(it)).append("\"")
        }
        append(">\n")

        append("        <BaseURL>").append(xmlEscapeText(baseUrl)).append("</BaseURL>\n")

        if (segmentBase != null) {
            append("        <SegmentBase indexRange=\"").append(xmlEscapeAttr(segmentBase.indexRange)).append("\">\n")
            append("          <Initialization range=\"").append(xmlEscapeAttr(segmentBase.initialization)).append("\" />\n")
            append("        </SegmentBase>\n")
        } else {
            append("        <SegmentBase />\n")
        }

        append("      </Representation>\n")
        append("    </AdaptationSet>\n")
    }

    private fun xmlEscapeText(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        return buildString(v.length + 16) {
            for (ch in v) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(ch)
                }
            }
        }
    }

    private fun xmlEscapeAttr(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        return buildString(v.length + 16) {
            for (ch in v) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }
    }

    private fun formatMpdDuration(durationMs: Long): String {
        val ms = durationMs.coerceAtLeast(0L)
        val sec = ms / 1000L
        val remMs = (ms % 1000L).toInt().coerceAtLeast(0)
        return if (remMs == 0) {
            "PT${sec}S"
        } else {
            val v = sec.toDouble() + (remMs.toDouble() / 1000.0)
            val fixed = String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
            "PT${fixed}S"
        }
    }
}
