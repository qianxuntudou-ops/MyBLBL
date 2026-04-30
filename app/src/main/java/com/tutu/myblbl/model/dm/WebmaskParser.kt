package com.tutu.myblbl.model.dm

import android.graphics.Path
import android.util.Base64
import com.tutu.myblbl.core.common.util.AppLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

object WebmaskParser {

    private const val TAG = "WebmaskParser"

    fun parse(data: ByteArray): DmMaskData? {
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

        // Read segment table
        val segments = mutableListOf<Pair<Long, Int>>()
        for (i in 0 until segmentCount) {
            val timeMs = buf.long
            val offset = buf.long.toInt()
            segments.add(timeMs to offset)
        }

        // Parse each segment
        val parsedSegments = mutableListOf<MaskSegment>()
        for (i in segments.indices) {
            val (timeMs, startOffset) = segments[i]
            val endOffset = if (i + 1 < segments.size) segments[i + 1].second else data.size
            if (startOffset < 0 || endOffset > data.size || startOffset >= endOffset) continue

            val segBytes = data.copyOfRange(startOffset, endOffset)
            val frames = parseSegment(segBytes, i) ?: continue
            parsedSegments.add(MaskSegment(timeMs = timeMs, frames = frames))
        }

        if (parsedSegments.isEmpty()) return null
        return DmMaskData(fps = 0, segments = parsedSegments)
    }

    private fun parseSegment(segBytes: ByteArray, segIndex: Int): List<MaskFrame>? {
        val decompressed = try {
            GZIPInputStream(segBytes.inputStream()).use { gzip ->
                val out = ByteArrayOutputStream()
                gzip.copyTo(out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "GZIP decompress failed for segment $segIndex: ${e.message}")
            return null
        }

        val separator = "data:image/svg+xml;base64,".toByteArray()
        val parts = splitBy(decompressed, separator)
        if (parts.size <= 1) return null

        val frames = mutableListOf<MaskFrame>()
        val frameIntervalMs = 33L // ~30fps default

        for (frameIdx in 1 until parts.size) {
            val b64Data = parts[frameIdx]
            val svgBytes = try {
                Base64.decode(b64Data, Base64.DEFAULT)
            } catch (e: Exception) {
                continue
            }
            val svgText = svgBytes.toString(Charsets.UTF_8)
            val paths = parseSvgPaths(svgText)
            if (paths.isNotEmpty()) {
                frames.add(MaskFrame(
                    relativeTimeMs = (frameIdx - 1) * frameIntervalMs,
                    paths = paths
                ))
            }
        }
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
            val tokens = d.trim().split(Regex("\\s+"))
            var i = 0
            var currentCommand = 'M'

            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    token.length == 1 && token[0] in "MLmlCc" -> {
                        currentCommand = token[0]
                        i++
                        continue
                    }
                    token == "z" || token == "Z" -> {
                        path.close()
                        i++
                        continue
                    }
                    token[0].isDigit() || token[0] == '-' -> { /* keep currentCommand, implicit */ }
                    else -> { i++; continue }
                }

                when (currentCommand) {
                    'M' -> {
                        if (i + 1 >= tokens.size) break
                        val (nx, ny) = normalize(tokens[i].toFloat(), tokens[i + 1].toFloat(), viewWidth, viewHeight)
                        path.moveTo(nx * viewWidth, ny * viewHeight)
                        i += 2
                    }
                    'L' -> {
                        if (i + 1 >= tokens.size) break
                        val (nx, ny) = normalize(tokens[i].toFloat(), tokens[i + 1].toFloat(), viewWidth, viewHeight)
                        path.lineTo(nx * viewWidth, ny * viewHeight)
                        i += 2
                    }
                    'm' -> {
                        if (i + 1 >= tokens.size) break
                        val (dx, dy) = normalize(tokens[i].toFloat(), tokens[i + 1].toFloat(), viewWidth, viewHeight)
                        path.rMoveTo(dx * viewWidth, dy * viewHeight)
                        i += 2
                    }
                    'l' -> {
                        if (i + 1 >= tokens.size) break
                        val (dx, dy) = normalize(tokens[i].toFloat(), tokens[i + 1].toFloat(), viewWidth, viewHeight)
                        path.rLineTo(dx * viewWidth, dy * viewHeight)
                        i += 2
                    }
                    'C' -> {
                        if (i + 5 >= tokens.size) break
                        val (x1, y1) = normalize(tokens[i].toFloat(), tokens[i + 1].toFloat(), viewWidth, viewHeight)
                        val (x2, y2) = normalize(tokens[i + 2].toFloat(), tokens[i + 3].toFloat(), viewWidth, viewHeight)
                        val (x3, y3) = normalize(tokens[i + 4].toFloat(), tokens[i + 5].toFloat(), viewWidth, viewHeight)
                        path.cubicTo(x1 * viewWidth, y1 * viewHeight, x2 * viewWidth, y2 * viewHeight, x3 * viewWidth, y3 * viewHeight)
                        i += 6
                    }
                    'c' -> {
                        if (i + 5 >= tokens.size) break
                        val (dx1, dy1) = normalize(tokens[i].toFloat(), tokens[i + 1].toFloat(), viewWidth, viewHeight)
                        val (dx2, dy2) = normalize(tokens[i + 2].toFloat(), tokens[i + 3].toFloat(), viewWidth, viewHeight)
                        val (dx3, dy3) = normalize(tokens[i + 4].toFloat(), tokens[i + 5].toFloat(), viewWidth, viewHeight)
                        path.rCubicTo(dx1 * viewWidth, dy1 * viewHeight, dx2 * viewWidth, dy2 * viewHeight, dx3 * viewWidth, dy3 * viewHeight)
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

    private fun normalize(svgX: Float, svgY: Float, viewWidth: Float, viewHeight: Float): Pair<Float, Float> {
        val pixelX = svgX * 0.1f
        val pixelY = viewHeight - svgY * 0.1f
        return (pixelX / viewWidth).coerceIn(0f, 1f) to (pixelY / viewHeight).coerceIn(0f, 1f)
    }

    private fun extractFloat(text: String, pattern: String): Float? {
        return Regex(pattern).find(text)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
