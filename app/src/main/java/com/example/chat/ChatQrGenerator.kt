package com.example.chat

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Pure-Kotlin QR Code generator (Byte mode, Error Correction Level M, Versions 1..5).
 * Generates scannable QR Bitmaps for group invite links without requiring external libraries.
 */
object ChatQrGenerator {

    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val totalCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocks: Int,
        val alignCenter: Int?
    )

    private val specs = listOf(
        VersionSpec(1, 21, 26, 10, 1, null),
        VersionSpec(2, 25, 44, 16, 1, 18),
        VersionSpec(3, 29, 70, 26, 1, 22),
        VersionSpec(4, 33, 100, 18, 2, 26),
        VersionSpec(5, 37, 134, 24, 2, 30)
    )

    // Precomputed GF(256) exp & log tables with primitive polynomial 0x11D
    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if ((x and 0x100) != 0) {
                x = x xor 0x11D
            }
        }
        for (i in 255 until 512) {
            gfExp[i] = gfExp[i - 255]
        }
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return gfExp[gfLog[a] + gfLog[b]]
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val factor = intArrayOf(1, gfExp[i])
            val next = IntArray(poly.size + 1)
            for (p in poly.indices) {
                for (q in factor.indices) {
                    next[p + q] = next[p + q] xor gfMul(poly[p], factor[q])
                }
            }
            poly = next
        }
        return poly
    }

    private fun rsRemainder(data: IntArray, ecLen: Int): IntArray {
        val gen = rsGeneratorPoly(ecLen)
        val res = IntArray(ecLen)
        for (b in data) {
            val factor = b xor res[0]
            System.arraycopy(res, 1, res, 0, ecLen - 1)
            res[ecLen - 1] = 0
            if (factor != 0) {
                for (j in 0 until ecLen) {
                    res[j] = res[j] xor gfMul(gen[j + 1], factor)
                }
            }
        }
        return res
    }

    fun generateQrBitmap(content: String, targetPixels: Int = 512): Bitmap {
        val payload = content.trim().ifBlank { "https://xmax.app" }.toByteArray(Charsets.UTF_8)
        val matrix = encodeMatrix(payload)
        val modules = matrix.size
        val quiet = 2
        val totalModules = modules + quiet * 2
        val scale = (targetPixels / totalModules).coerceAtLeast(4)
        val imgSize = totalModules * scale
        val pixels = IntArray(imgSize * imgSize) { Color.WHITE }

        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (matrix[y][x]) {
                    val startX = (x + quiet) * scale
                    val startY = (y + quiet) * scale
                    for (dy in 0 until scale) {
                        val rowOffset = (startY + dy) * imgSize + startX
                        for (dx in 0 until scale) {
                            pixels[rowOffset + dx] = Color.BLACK
                        }
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, imgSize, imgSize, Bitmap.Config.ARGB_8888)
    }

    internal fun encodeMatrix(bytes: ByteArray): Array<BooleanArray> {
        val spec = specs.firstOrNull {
            val dataCodewords = it.totalCodewords - it.ecCodewordsPerBlock * it.numBlocks
            // 4 bits mode + 8 bits length + payload + 4 bits terminator <= dataCodewords * 8
            (4 + 8 + bytes.size * 8 + 4) <= dataCodewords * 8
        } ?: specs.last()

        val maxDataCodewords = spec.totalCodewords - spec.ecCodewordsPerBlock * spec.numBlocks
        val trimmed = if (bytes.size > maxDataCodewords - 2) bytes.copyOf(maxDataCodewords - 2) else bytes

        val bitBuffer = mutableListOf<Int>()
        fun pushBits(value: Int, count: Int) {
            for (i in (count - 1) downTo 0) {
                bitBuffer += (value shr i) and 1
            }
        }

        // Mode indicator: Byte = 0100
        pushBits(0b0100, 4)
        pushBits(trimmed.size, 8)
        trimmed.forEach { pushBits(it.toInt() and 0xFF, 8) }

        val capacityBits = maxDataCodewords * 8
        val terminator = minOf(4, capacityBits - bitBuffer.size)
        repeat(terminator) { bitBuffer += 0 }
        while (bitBuffer.size % 8 != 0) bitBuffer += 0
        var padByte = 0xEC
        while (bitBuffer.size < capacityBits) {
            pushBits(padByte, 8)
            padByte = if (padByte == 0xEC) 0x11 else 0xEC
        }

        val dataCodewords = IntArray(maxDataCodewords) { i ->
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or bitBuffer[i * 8 + b]
            v
        }

        val blockSize = maxDataCodewords / spec.numBlocks
        val blocks = Array(spec.numBlocks) { idx ->
            dataCodewords.copyOfRange(idx * blockSize, (idx + 1) * blockSize)
        }
        val ecBlocks = Array(spec.numBlocks) { idx ->
            rsRemainder(blocks[idx], spec.ecCodewordsPerBlock)
        }

        val interleaved = mutableListOf<Int>()
        for (i in 0 until blockSize) {
            for (b in 0 until spec.numBlocks) interleaved += blocks[b][i]
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) interleaved += ecBlocks[b][i]
        }

        val allBits = mutableListOf<Int>()
        interleaved.forEach { cw ->
            for (i in 7 downTo 0) allBits += (cw shr i) and 1
        }

        val n = spec.size
        val modules = Array(n) { BooleanArray(n) }
        val isFunction = Array(n) { BooleanArray(n) }

        fun setFn(r: Int, c: Int, black: Boolean) {
            if (r in 0 until n && c in 0 until n) {
                modules[r][c] = black
                isFunction[r][c] = true
            }
        }

        fun drawFinder(row: Int, col: Int) {
            for (dy in -1..7) {
                for (dx in -1..7) {
                    val rr = row + dy
                    val cc = col + dx
                    if (rr !in 0 until n || cc !in 0 until n) continue
                    val black = (dy in 0..6 && dx in 0..6) && (
                        dy == 0 || dy == 6 || dx == 0 || dx == 6 || (dy in 2..4 && dx in 2..4)
                    )
                    setFn(rr, cc, black)
                }
            }
        }

        drawFinder(0, 0)
        drawFinder(0, n - 7)
        drawFinder(n - 7, 0)

        spec.alignCenter?.let { center ->
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val black = kotlin.math.max(kotlin.math.abs(dy), kotlin.math.abs(dx)) != 1
                    setFn(center + dy, center + dx, black)
                }
            }
        }

        for (i in 8 until n - 8) {
            setFn(6, i, i % 2 == 0)
            setFn(i, 6, i % 2 == 0)
        }

        // Format placeholders
        for (i in 0..8) {
            if (i != 6) {
                setFn(8, i, false)
                setFn(i, 8, false)
            }
        }
        for (i in (n - 8) until n) {
            setFn(8, i, false)
            setFn(i, 8, false)
        }
        setFn(n - 8, 8, true) // dark module

        // Place data bits (right-to-left 2-col zig-zag)
        var bitIdx = 0
        var right = n - 1
        while (right >= 1) {
            if (right == 6) right--
            for (vert in 0 until n) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) n - 1 - vert else vert
                    if (!isFunction[y][x] && bitIdx < allBits.size) {
                        modules[y][x] = allBits[bitIdx] == 1
                        bitIdx++
                    }
                }
            }
            right -= 2
        }

        // Apply mask 0 ((y + x) % 2 == 0)
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (!isFunction[y][x] && ((y + x) % 2 == 0)) {
                    modules[y][x] = !modules[y][x]
                }
            }
        }

        // Write Format Bits for Level M (00) + Mask 0 (000) -> 15-bit BCH + XOR 0x5412 = 0x5067 -> 101010000010010? Wait compute exact:
        val fmtData = (0 shl 3) or 0 // EC M=00, Mask 0=000
        var rem = fmtData shl 10
        for (i in 4 downTo 0) {
            if (((rem ushr (i + 10)) and 1) != 0) {
                rem = rem xor (0x537 shl i)
            }
        }
        val fmtBits = ((fmtData shl 10) or rem) xor 0x5412
        for (i in 0..5) setFn(8, i, ((fmtBits ushr i) and 1) != 0)
        setFn(8, 7, ((fmtBits ushr 6) and 1) != 0)
        setFn(8, 8, ((fmtBits ushr 7) and 1) != 0)
        setFn(7, 8, ((fmtBits ushr 8) and 1) != 0)
        for (i in 9..14) setFn(14 - i, 8, ((fmtBits ushr i) and 1) != 0)

        for (i in 0..7) setFn(n - 1 - i, 8, ((fmtBits ushr i) and 1) != 0)
        for (i in 8..14) setFn(8, n - 15 + i, ((fmtBits ushr i) and 1) != 0)

        return modules
    }
}
