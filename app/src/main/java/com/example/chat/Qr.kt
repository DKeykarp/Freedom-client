package com.example.chat

/**
 * Минимальный QR-энкодер (ISO/IEC 18004): байтовый режим, версии 1-6, уровень коррекции M.
 * Портировано построчно с эталонной реализации (server/web/qr.js), которую независимо проверили
 * побитовым сравнением с Python-прототипом и декодированием через OpenCV (cv2.QRCodeDetector) на
 * широком наборе случайных строк — при первой сборке стоит один раз сверить визуально с веб-версией.
 */
object Qr {
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x; LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun gmul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun rsGeneratorPoly(nsym: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until nsym) {
            val g2 = IntArray(g.size + 1)
            for (j in g.indices) {
                g2[j] = g2[j] xor gmul(g[j], EXP[i])
                g2[j + 1] = g2[j + 1] xor g[j]
            }
            g = g2
        }
        g.reverse()   // «от старшей степени к младшей» — в этом порядке её использует rsEncode
        return g
    }

    private fun rsEncode(data: IntArray, nsym: Int): IntArray {
        val gen = rsGeneratorPoly(nsym)
        val res = data.copyOf(data.size + nsym)
        for (i in data.indices) {
            val coef = res[i]
            if (coef != 0) {
                for (j in gen.indices) res[i + j] = res[i + j] xor gmul(gen[j], coef)
            }
        }
        return res.copyOfRange(data.size, res.size)
    }

    // версия -> (всего кодовых слов, EC-слов на блок, список [число блоков, слов данных на блок])
    private data class VerInfo(val total: Int, val ecPerBlock: Int, val blocks: List<Pair<Int, Int>>)
    private val VERSION_TABLE = mapOf(
        1 to VerInfo(26, 10, listOf(1 to 16)),
        2 to VerInfo(44, 16, listOf(1 to 28)),
        3 to VerInfo(70, 26, listOf(1 to 44)),
        4 to VerInfo(100, 18, listOf(2 to 32)),
        5 to VerInfo(134, 24, listOf(2 to 43)),
        6 to VerInfo(172, 16, listOf(4 to 27)),
    )
    private val ALIGN_POS = mapOf(
        1 to listOf(), 2 to listOf(6, 18), 3 to listOf(6, 22),
        4 to listOf(6, 26), 5 to listOf(6, 30), 6 to listOf(6, 34),
    )

    private fun pickVersion(nbytes: Int): Int {
        for (v in 1..6) {
            val dataCap = VERSION_TABLE.getValue(v).blocks.sumOf { (n, c) -> n * c }
            if (4 + 8 + nbytes * 8 <= dataCap * 8) return v
        }
        throw IllegalArgumentException("Строка слишком длинная для версий 1-6")
    }

    private fun encodeBits(bytes: ByteArray, version: Int): IntArray {
        val dataCapBytes = VERSION_TABLE.getValue(version).blocks.sumOf { (n, c) -> n * c }
        val sb = StringBuilder("0100").append(bytes.size.toString(2).padStart(8, '0'))
        for (b in bytes) sb.append((b.toInt() and 0xFF).toString(2).padStart(8, '0'))
        val room = dataCapBytes * 8 - sb.length
        sb.append("0000".substring(0, maxOf(0, minOf(4, room))))
        while (sb.length % 8 != 0) sb.append('0')
        val codewords = ArrayList<Int>()
        var i = 0
        while (i < sb.length) { codewords.add(sb.substring(i, i + 8).toInt(2)); i += 8 }
        val pad = intArrayOf(0xEC, 0x11)
        var pi = 0
        while (codewords.size < dataCapBytes) { codewords.add(pad[pi % 2]); pi++ }
        return codewords.toIntArray()
    }

    private fun interleave(codewords: IntArray, version: Int): IntArray {
        val info = VERSION_TABLE.getValue(version)
        val dataBlocks = ArrayList<IntArray>()
        val ecBlocks = ArrayList<IntArray>()
        var pos = 0
        for ((n, c) in info.blocks) {
            repeat(n) {
                val block = codewords.copyOfRange(pos, pos + c); pos += c
                dataBlocks.add(block)
                ecBlocks.add(rsEncode(block, info.ecPerBlock))
            }
        }
        val result = ArrayList<Int>()
        val maxLen = dataBlocks.maxOf { it.size }
        for (i in 0 until maxLen) for (b in dataBlocks) if (i < b.size) result.add(b[i])
        for (i in 0 until info.ecPerBlock) for (b in ecBlocks) result.add(b[i])
        return result.toIntArray()
    }

    private const val FORMAT_GEN = 0x537
    private const val FORMAT_MASK = 0x5412

    private fun formatBits(ecLevelBits: Int, maskPattern: Int): IntArray {
        val data = (ecLevelBits shl 3) or maskPattern
        var rem = data shl 10
        for (i in 14 downTo 10) {
            if (rem and (1 shl i) != 0) rem = rem xor (FORMAT_GEN shl (i - 10))
        }
        val full = ((data shl 10) or rem) xor FORMAT_MASK
        return IntArray(15) { (full shr (14 - it)) and 1 }
    }

    private class Matrix(val size: Int) {
        val m = Array(size) { IntArray(size) { -1 } }         // -1 = ещё не занято
        val isFunc = Array(size) { BooleanArray(size) }
    }

    private fun buildMatrix(version: Int, finalCodewords: IntArray): Matrix {
        val size = 17 + version * 4
        val g = Matrix(size)

        fun setFinder(r0: Int, c0: Int) {
            for (dr in -1..7) for (dc in -1..7) {
                val rr = r0 + dr; val cc = c0 + dc
                if (rr !in 0 until size || cc !in 0 until size) continue
                g.isFunc[rr][cc] = true
                g.m[rr][cc] = if (dr in 0..6 && dc in 0..6) {
                    if (dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4)) 1 else 0
                } else 0
            }
        }
        setFinder(0, 0); setFinder(0, size - 7); setFinder(size - 7, 0)

        val aps = ALIGN_POS.getValue(version)
        for (r in aps) for (c in aps) {
            if ((r < 9 && c < 9) || (r < 9 && c > size - 9) || (r > size - 9 && c < 9)) continue
            for (dr in -2..2) for (dc in -2..2) {
                val on = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != 1
                g.m[r + dr][c + dc] = if (on) 1 else 0
                g.isFunc[r + dr][c + dc] = true
            }
        }

        for (i in 8 until size - 8) {
            g.m[6][i] = 1 - (i % 2); g.isFunc[6][i] = true
            g.m[i][6] = 1 - (i % 2); g.isFunc[i][6] = true
        }
        g.m[size - 8][8] = 1; g.isFunc[size - 8][8] = true

        for (i in 0 until 9) {
            if (g.m[8][i] == -1) g.m[8][i] = 0
            g.isFunc[8][i] = true
            if (g.m[i][8] == -1) g.m[i][8] = 0
            g.isFunc[i][8] = true
        }
        for (i in 0 until 8) {
            if (g.m[8][size - 1 - i] == -1) g.m[8][size - 1 - i] = 0
            g.isFunc[8][size - 1 - i] = true
            if (g.m[size - 1 - i][8] == -1) g.m[size - 1 - i][8] = 0
            g.isFunc[size - 1 - i][8] = true
        }

        val bits = ArrayList<Int>()
        for (cw in finalCodewords) for (i in 7 downTo 0) bits.add((cw shr i) and 1)
        while (bits.size < size * size) bits.add(0)
        var bitI = 0; var col = size - 1; var goingUp = true
        while (col > 0) {
            if (col == 6) col -= 1
            val rows = if (goingUp) (size - 1 downTo 0) else (0 until size)
            for (row in rows) {
                for (c in intArrayOf(col, col - 1)) {
                    if (g.m[row][c] == -1) g.m[row][c] = if (bitI < bits.size) bits[bitI++] else 0
                }
            }
            goingUp = !goingUp
            col -= 2
        }
        return g
    }

    private val MASKS: List<(Int, Int) -> Boolean> = listOf(
        { r, c -> (r + c) % 2 == 0 },
        { r, _ -> r % 2 == 0 },
        { _, c -> c % 3 == 0 },
        { r, c -> (r + c) % 3 == 0 },
        { r, c -> (r / 2 + c / 3) % 2 == 0 },
        { r, c -> (r * c) % 2 + (r * c) % 3 == 0 },
        { r, c -> ((r * c) % 2 + (r * c) % 3) % 2 == 0 },
        { r, c -> ((r + c) % 2 + (r * c) % 3) % 2 == 0 },
    )

    private fun applyMask(base: Matrix, maskFn: (Int, Int) -> Boolean): Array<IntArray> =
        Array(base.size) { r -> IntArray(base.size) { c -> if (!base.isFunc[r][c] && maskFn(r, c)) base.m[r][c] xor 1 else base.m[r][c] } }

    private fun penalty(m: Array<IntArray>, size: Int): Int {
        var score = 0
        for (r in 0 until size) {
            var run = 1
            for (c in 1 until size) {
                if (m[r][c] == m[r][c - 1]) run++ else { if (run >= 5) score += run - 2; run = 1 }
            }
            if (run >= 5) score += run - 2
        }
        for (c in 0 until size) {
            var run = 1
            for (r in 1 until size) {
                if (m[r][c] == m[r - 1][c]) run++ else { if (run >= 5) score += run - 2; run = 1 }
            }
            if (run >= 5) score += run - 2
        }
        for (r in 0 until size - 1) for (c in 0 until size - 1) {
            val v = m[r][c]
            if (m[r][c + 1] == v && m[r + 1][c] == v && m[r + 1][c + 1] == v) score += 3
        }
        var dark = 0
        for (row in m) for (v in row) dark += v
        val pct = (dark * 100) / (size * size)
        score += (kotlin.math.abs(pct - 50) / 5) * 10
        return score
    }

    /** true = тёмный модуль. Текст — как есть, в UTF-8 (для наших username хватает и ASCII). */
    fun encode(text: String): Pair<Array<BooleanArray>, Int> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val version = pickVersion(bytes.size)
        val cw = encodeBits(bytes, version)
        val final = interleave(cw, version)
        val base = buildMatrix(version, final)

        var bestP = Int.MAX_VALUE; var bestMi = 0; var bestM: Array<IntArray> = base.m
        for (mi in MASKS.indices) {
            val cand = applyMask(base, MASKS[mi])
            val p = penalty(cand, base.size)
            if (p < bestP) { bestP = p; bestMi = mi; bestM = cand }
        }

        val fbits = formatBits(0b00, bestMi)
        val fposA = arrayOf(8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8, 7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8)
        fposA.forEachIndexed { i, (r, c) -> bestM[r][c] = fbits[i] }
        val size = base.size
        val fposB = arrayOf(
            size - 1 to 8, size - 2 to 8, size - 3 to 8, size - 4 to 8, size - 5 to 8, size - 6 to 8, size - 7 to 8,
            8 to size - 8, 8 to size - 7, 8 to size - 6, 8 to size - 5, 8 to size - 4, 8 to size - 3, 8 to size - 2, 8 to size - 1,
        )
        fposB.forEachIndexed { i, (r, c) -> bestM[r][c] = fbits[i] }

        val boolM = Array(size) { r -> BooleanArray(size) { c -> bestM[r][c] == 1 } }
        return boolM to size
    }
}
