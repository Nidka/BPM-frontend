package pe.bpm.core.crypto

import kotlinx.coroutines.yield

/**
 * SHA-256 en Kotlin puro: el mismo resultado en Android, iOS, web y pruebas JVM, sin copiar
 * el archivo entre el motor de la plataforma y la memoria compartida. Se usa para los videos de
 * capacitación; calcula por bloques y cede el hilo entre tramos para no congelar la interfaz.
 */
object Sha256 {
    private val k: IntArray = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()

    private const val TRAMO = 4 * 1024 * 1024

    /** Digest hexadecimal en minúsculas (64 caracteres). */
    fun hex(bytes: ByteArray): String = Calculo().also { it.update(bytes, 0, bytes.size) }.hex()

    /** Igual que [hex], cediendo el hilo cada 4 MiB para que la interfaz siga respondiendo. */
    suspend fun hexCediendo(bytes: ByteArray): String {
        val calculo = Calculo()
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(TRAMO, bytes.size - offset)
            calculo.update(bytes, offset, count)
            offset += count
            yield()
        }
        return calculo.hex()
    }

    /** Cálculo incremental: permite verificar archivos grandes por bloques sin cargarlos en memoria. */
    class Calculo {
        private val h = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(), 0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
        private val block = ByteArray(64)
        private var filled = 0
        private var total = 0L
        private val w = IntArray(64)

        fun update(data: ByteArray, start: Int = 0, count: Int = data.size) {
            total += count
            var index = start
            val end = start + count
            while (index < end) {
                val take = minOf(64 - filled, end - index)
                data.copyInto(block, filled, index, index + take)
                filled += take
                index += take
                if (filled == 64) { compress(); filled = 0 }
            }
        }

        fun hex(): String {
            val bits = total * 8
            block[filled++] = 0x80.toByte()
            if (filled > 56) {
                while (filled < 64) block[filled++] = 0
                compress(); filled = 0
            }
            while (filled < 56) block[filled++] = 0
            for (shift in 7 downTo 0) block[filled++] = (bits ushr (shift * 8)).toByte()
            compress()
            return h.joinToString("") { word -> (0 until 4).joinToString("") { i ->
                ((word ushr (24 - i * 8)) and 0xff).toString(16).padStart(2, '0') } }
        }

        private fun rotr(x: Int, n: Int) = (x ushr n) or (x shl (32 - n))

        private fun compress() {
            for (i in 0 until 16) {
                w[i] = ((block[i * 4].toInt() and 0xff) shl 24) or ((block[i * 4 + 1].toInt() and 0xff) shl 16) or
                    ((block[i * 4 + 2].toInt() and 0xff) shl 8) or (block[i * 4 + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + k[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
    }
}
