package com.tutu.myblbl.model.dm

import android.graphics.Path
import android.util.Base64
import com.tutu.myblbl.core.common.log.AppLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

object WebmaskParser {

    private const val TAG = "WebmaskParser"

    fun parse(data: ByteArray, fps: Int = 0): DmMaskData? {
        if (data.size < 16) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val header = ByteArray(4)
        buf.get(header)
        if (!header.contentEquals("MASK".toByteArray())) {
            AppLog.e(TAG, "Invalid webmask header")
            return null
        }

        val version = buf.int
        buf.int // vU, skip
        val segmentCount = buf.int
        if (segmentCount <= 0 || segmentCount > 1000) {
            AppLog.e(TAG, "Invalid segment count: $segmentCount")
            return null
        }

        // 只读 segment 索引表，不解析帧数据
        val segments = mutableListOf<LazyMaskSegment>()
        for (i in 0 until segmentCount) {
            val timeMs = buf.long
            val offset = buf.long.toInt()
            val endOffset = if (i + 1 < segmentCount) {
                // 需要知道下一个 segment 的 offset，先存临时值
                offset // placeholder
            } else {
                data.size
            }
            segments.add(LazyMaskSegment(timeMs = timeMs, startOffset = offset, endOffset = 0))
        }

        // 回填 endOffset
        for (i in segments.indices) {
            val endOff = if (i + 1 < segments.size) segments[i + 1].startOffset else data.size
            segments[i] = segments[i].copy(endOffset = endOff, rawData = data)
        }

        if (segments.isEmpty()) return null

        // 诊断日志
        AppLog.d(TAG, "Lazy parse done: ${segments.size} segments indexed")
        segments.take(3).forEach {
            AppLog.d(TAG, "seg: timeMs=${it.timeMs}, bytes=${it.endOffset - it.startOffset}")
        }

        return DmMaskData(fps = fps, rawSegments = segments)
    }

    fun parseSegmentFrames(segment: LazyMaskSegment, fps: Int): List<MaskFrame>? {
        val data = segment.rawData ?: return null
        val startOffset = segment.startOffset
        val endOffset = segment.endOffset
        if (startOffset < 0 || endOffset > data.size || startOffset >= endOffset) return null

        val segBytes = data.copyOfRange(startOffset, endOffset)
        val decompressed = try {
            GZIPInputStream(segBytes.inputStream()).use { gzip ->
                val out = ByteArrayOutputStream()
                gzip.copyTo(out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "GZIP decompress failed: ${e.message}")
            return null
        }

        val separator = "data:image/svg+xml;base64,".toByteArray()
        val parts = splitBy(decompressed, separator)
        if (parts.size <= 1) return null

        var diagLogged = false
        var emptyCount = 0
        val frames = mutableListOf<MaskFrame>()
        for (frameIdx in 1 until parts.size) {
            val b64Data = parts[frameIdx]
            val svgBytes = try {
                Base64.decode(b64Data, Base64.DEFAULT)
            } catch (e: Exception) {
                frames.add(MaskFrame(paths = emptyList()))
                emptyCount++
                continue
            }
            val svgText = svgBytes.toString(Charsets.UTF_8)
            if (!diagLogged) {
                AppLog.d(TAG, "SVG frame[0] preview: ${svgText.take(300).replace('\n', ' ')}")
                diagLogged = true
            }
            val paths = parseSvgPaths(svgText)
            if (paths.isEmpty()) emptyCount++
            frames.add(MaskFrame(paths = paths))
        }

        // 前向填充：空帧用前一个有 path 的帧替代，避免遮罩冻结
        var lastPaths: List<Path>? = null
        for (i in frames.indices) {
            if (frames[i].paths.isNotEmpty()) {
                lastPaths = frames[i].paths
            } else if (lastPaths != null) {
                frames[i] = MaskFrame(paths = lastPaths)
                emptyCount--
            }
        }

        AppLog.d(TAG, "parseSegmentFrames: total=${frames.size}, withPaths=${frames.size - emptyCount}, remainingEmpty=$emptyCount")
        return frames.takeIf { it.isNotEmpty() }
    }

    private fun splitBy(data: ByteArray, delimiter: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0
        var i = 0
        while (i <= data.size - delimiter.size) {
            var match = true
            for (j in delimiter.indices) {
                if (data[i + j] != delimiter[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                result.add(data.copyOfRange(start, i))
                start = i + delimiter.size
                i = start
            } else {
                i++
            }
        }
        result.add(data.copyOfRange(start, data.size))
        return result
    }

    private fun parseSvgPaths(svgText: String): List<Path> {
        val viewWidth = extractFloat(svgText, """width="([\d.]+)px"""") ?: return emptyList()
        val viewHeight = extractFloat(svgText, """height="([\d.]+)px"""") ?: return emptyList()
        if (viewWidth <= 0f || viewHeight <= 0f) return emptyList()

        val pathRegex = Regex("""<path\s+d="([^"]+)"""")
        val results = mutableListOf<Path>()

        for (match in pathRegex.findAll(svgText)) {
            val d = match.groupValues[1]
            val path = svgPathToAndroidPath(d, viewWidth, viewHeight)
            if (path != null) {
                results.add(path)
            }
        }
        return results
    }

    private fun svgPathToAndroidPath(d: String, viewWidth: Float, viewHeight: Float): Path? {
        try {
            val path = Path()
            val tokens = tokenizeSvgPath(d.trim())
            var i = 0
            var currentCommand = 'M'

            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    token.length == 1 && token[0] in "MLmlCcSsQqTtAaHhVv" -> {
                        currentCommand = token[0]
                        i++
                        continue
                    }
                    token == "z" || token == "Z" -> {
                        path.close()
                        i++
                        continue
                    }
                    token[0].isDigit() || token[0] == '-' || token[0] == '.' -> { /* keep currentCommand */ }
                    else -> { i++; continue }
                }

                when (currentCommand) {
                    'M' -> {
                        if (i + 1 >= tokens.size) break
                        path.moveTo(tokens[i].toFloat() * 0.1f, viewHeight - tokens[i + 1].toFloat() * 0.1f)
                        i += 2
                    }
                    'L' -> {
                        if (i + 1 >= tokens.size) break
                        path.lineTo(tokens[i].toFloat() * 0.1f, viewHeight - tokens[i + 1].toFloat() * 0.1f)
                        i += 2
                    }
                    'm' -> {
                        if (i + 1 >= tokens.size) break
                        path.rMoveTo(tokens[i].toFloat() * 0.1f, tokens[i + 1].toFloat() * -0.1f)
                        i += 2
                    }
                    'l' -> {
                        if (i + 1 >= tokens.size) break
                        path.rLineTo(tokens[i].toFloat() * 0.1f, tokens[i + 1].toFloat() * -0.1f)
                        i += 2
                    }
                    'C' -> {
                        if (i + 5 >= tokens.size) break
                        path.cubicTo(
                            tokens[i].toFloat() * 0.1f, viewHeight - tokens[i + 1].toFloat() * 0.1f,
                            tokens[i + 2].toFloat() * 0.1f, viewHeight - tokens[i + 3].toFloat() * 0.1f,
                            tokens[i + 4].toFloat() * 0.1f, viewHeight - tokens[i + 5].toFloat() * 0.1f
                        )
                        i += 6
                    }
                    'c' -> {
                        if (i + 5 >= tokens.size) break
                        path.rCubicTo(
                            tokens[i].toFloat() * 0.1f, tokens[i + 1].toFloat() * -0.1f,
                            tokens[i + 2].toFloat() * 0.1f, tokens[i + 3].toFloat() * -0.1f,
                            tokens[i + 4].toFloat() * 0.1f, tokens[i + 5].toFloat() * -0.1f
                        )
                        i += 6
                    }
                    else -> i++
                }
            }
            return path
        } catch (e: Exception) {
            AppLog.e(TAG, "SVG path parse error: ${e.message}")
            return null
        }
    }

    private fun tokenizeSvgPath(d: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        val commands = "MmLlCcSsQqTtAaHhVvZz"

        for (ch in d) {
            if (ch in commands) {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
                tokens.add(ch.toString())
            } else if (ch.isWhitespace() || ch == ',') {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
        }
        return tokens
    }

    private fun extractFloat(text: String, pattern: String): Float? {
        return Regex(pattern).find(text)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
