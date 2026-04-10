package com.tutu.myblbl.model.proto

import com.google.protobuf.CodedInputStream

object DmProtoParser {

    fun parseSegment(bytes: ByteArray): DmSegMobileReplyProto {
        val input = CodedInputStream.newInstance(bytes)
        val elems = mutableListOf<DanmakuElemProto>()
        var state = 0
        var aiFlag = DanmakuAIFlagProto()
        val colorfulSrc = mutableListOf<DmColorfulProto>()
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                else -> {
                    when (tag ushr 3) {
                        1 -> elems += parseElem(input.readByteArray())
                        2 -> state = input.readInt32()
                        3 -> aiFlag = parseAiFlag(input.readByteArray())
                        5 -> colorfulSrc += parseColorful(input.readByteArray())
                        else -> input.skipField(tag)
                    }
                }
            }
        }
        return DmSegMobileReplyProto(
            elems = elems,
            state = state,
            aiFlag = aiFlag,
            colorfulSrc = colorfulSrc
        )
    }

    fun parseView(bytes: ByteArray): DmWebViewReplyProto {
        val input = CodedInputStream.newInstance(bytes)
        var segmentDurationMs = 0
        var totalSegments = 0
        var totalCount = 0L
        val specialDanmakuUrls = mutableListOf<String>()
        var smartFilterConfig = DmSmartFilterConfigProto()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                else -> {
                    when (tag ushr 3) {
                        4 -> {
                            val dmSge = parseDmSge(input.readByteArray())
                            segmentDurationMs = dmSge.first
                            totalSegments = dmSge.second
                        }
                        5 -> smartFilterConfig = parseDanmakuFlagConfig(input.readByteArray())
                        6 -> specialDanmakuUrls += input.readString()
                        10 -> {
                            val parsed = parseDanmuWebPlayerConfig(input.readByteArray())
                            smartFilterConfig = smartFilterConfig.copy(
                                playerLevel = parsed.first,
                                playerEnabled = parsed.second
                            )
                        }
                        8 -> totalCount = input.readInt64()
                        else -> input.skipField(tag)
                    }
                }
            }
        }

        return DmWebViewReplyProto(
            segmentDurationMs = segmentDurationMs,
            totalSegments = totalSegments,
            totalCount = totalCount,
            specialDanmakuUrls = specialDanmakuUrls,
            smartFilterConfig = smartFilterConfig
        )
    }

    private fun parseDanmakuFlagConfig(bytes: ByteArray): DmSmartFilterConfigProto {
        val input = CodedInputStream.newInstance(bytes)
        var cloudLevel = 0
        var cloudText = ""
        var cloudSwitch = 0
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                else -> {
                    when (tag ushr 3) {
                        1 -> cloudLevel = input.readInt32()
                        2 -> cloudText = input.readString()
                        3 -> cloudSwitch = input.readInt32()
                        else -> input.skipField(tag)
                    }
                }
            }
        }
        return DmSmartFilterConfigProto(
            cloudLevel = cloudLevel,
            cloudText = cloudText,
            cloudSwitch = cloudSwitch
        )
    }

    private fun parseDanmuWebPlayerConfig(bytes: ByteArray): Pair<Int, Boolean> {
        val input = CodedInputStream.newInstance(bytes)
        var enabled = false
        var level = 0
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                else -> {
                    when (tag ushr 3) {
                        2 -> enabled = input.readBool()
                        3 -> level = input.readInt32()
                        else -> input.skipField(tag)
                    }
                }
            }
        }
        return level to enabled
    }

    private fun parseElem(bytes: ByteArray): DanmakuElemProto {
        val input = CodedInputStream.newInstance(bytes)
        var id = 0L
        var progress = 0
        var mode = 1
        var fontSize = 25
        var color = 0xFFFFFFFF.toInt()
        var colorful = 0
        var midHash = ""
        var content = ""
        var ctime = 0L
        var weight = 0
        var pool = 0
        var action = ""
        var attr = 0
        var idStr = ""
        var animation = ""

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                else -> {
                    when (tag ushr 3) {
                        1 -> id = input.readInt64()
                        2 -> progress = input.readInt32()
                        3 -> mode = input.readInt32()
                        4 -> fontSize = input.readInt32()
                        5 -> color = input.readUInt32().toInt()
                        24 -> colorful = input.readInt32()
                        6 -> midHash = input.readString()
                        7 -> content = input.readString()
                        8 -> ctime = input.readInt64()
                        9 -> weight = input.readInt32()
                        10 -> action = input.readString()
                        11 -> pool = input.readInt32()
                        13 -> attr = input.readInt32()
                        12 -> idStr = input.readString()
                        14 -> animation = input.readString()
                        else -> input.skipField(tag)
                    }
                }
            }
        }

        return DanmakuElemProto(
            id = id,
            progress = progress,
            mode = mode,
            fontSize = fontSize,
            color = color,
            colorful = colorful,
            midHash = midHash,
            content = content,
            ctime = ctime,
            weight = weight,
            pool = pool,
            action = action,
            attr = attr,
            idStr = idStr,
            animation = animation
        )
    }

    private fun parseDmSge(bytes: ByteArray): Pair<Int, Int> {
        val input = CodedInputStream.newInstance(bytes)
        var pageSize = 0
        var totalSegments = 0
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                else -> {
                    when (tag ushr 3) {
                        1 -> pageSize = input.readInt32()
                        2 -> totalSegments = input.readInt32()
                        else -> input.skipField(tag)
                    }
                }
            }
        }
        return pageSize to totalSegments
    }

    private fun parseAiFlag(bytes: ByteArray): DanmakuAIFlagProto {
        val input = CodedInputStream.newInstance(bytes)
        val dmFlags = mutableListOf<DanmakuFlagProto>()
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                else -> {
                    when (tag ushr 3) {
                        1 -> dmFlags += parseAiFlagItem(input.readByteArray())
                        else -> input.skipField(tag)
                    }
                }
            }
        }
        return DanmakuAIFlagProto(dmFlags = dmFlags)
    }

    private fun parseAiFlagItem(bytes: ByteArray): DanmakuFlagProto {
        val input = CodedInputStream.newInstance(bytes)
        var dmid = 0L
        var flag = 0
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                else -> {
                    when (tag ushr 3) {
                        1 -> dmid = input.readInt64()
                        2 -> flag = input.readUInt32().toInt()
                        else -> input.skipField(tag)
                    }
                }
            }
        }
        return DanmakuFlagProto(dmid = dmid, flag = flag)
    }

    private fun parseColorful(bytes: ByteArray): DmColorfulProto {
        val input = CodedInputStream.newInstance(bytes)
        var type = 0
        var src = ""
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                else -> {
                    when (tag ushr 3) {
                        1 -> type = input.readInt32()
                        2 -> src = input.readString()
                        else -> input.skipField(tag)
                    }
                }
            }
        }
        return DmColorfulProto(type = type, src = src)
    }
}
