package com.iqoo.perfcollect.ml

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal .npy v1.0 reader for little-endian float32 arrays (pure Kotlin, no deps). */
internal object NpyReader {

    private fun readFully(inp: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = inp.read(buf, off, buf.size - off)
            if (r == -1) return false
            off += r
        }
        return true
    }

    /** reads a row-major '<f4' array with exactly [rows]x[cols] elements;
     *  returns null on ANY format/shape mismatch or truncated data */
    fun readFloat32(inp: InputStream, rows: Int, cols: Int): FloatArray? {
        return try {
            val head = ByteArray(10)
            if (!readFully(inp, head)) return null
            val magic = byteArrayOf(0x93.toByte(), 0x4E.toByte(), 0x55.toByte(), 0x4D.toByte(), 0x50.toByte(), 0x59.toByte()) // \x93NUMPY
            for (i in magic.indices) if (head[i] != magic[i]) return null
            if (head[6] != 1.toByte() || head[7] != 0.toByte()) return null // version 1.0 only
            val hdrLen = (head[8].toInt() and 0xFF) or ((head[9].toInt() and 0xFF) shl 8)
            val hdr = ByteArray(hdrLen)
            if (!readFully(inp, hdr)) return null
            val h = String(hdr, Charsets.US_ASCII)
            val descr = quoted(h, "descr") ?: return null
            if (descr != "<f4" && descr != "|f4") return null
            val shape = shapeOf(h) ?: return null
            if (shape[0] != rows || shape[1] != cols) return null
            val n = rows * cols
            val data = ByteArray(n * 4)
            var off = 0
            while (off < data.size) {
                val r = inp.read(data, off, data.size - off)
                if (r < 0) return null
                off += r
            }
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(n) { bb.getFloat(it * 4) }
        } catch (_: Exception) {
            null
        }
    }

    /** value of the 'key': '...' string in the npy header dict */
    private fun quoted(h: String, key: String): String? {
        val k1 = h.indexOf("'$key'")
        val k2 = h.indexOf("\"$key\"")
        val k = if (k1 >= 0) k1 else k2
        if (k < 0) return null
        val colon = h.indexOf(':', k + key.length + 2)
        if (colon < 0) return null
        val openSingle = h.indexOf('\'', colon)
        val openDouble = h.indexOf('"', colon)
        val (open, delim) = when {
            openSingle in 0..if (openDouble >= 0) openDouble else Int.MAX_VALUE -> openSingle to '\''
            openDouble >= 0 -> openDouble to '"'
            else -> return null
        }
        val close = h.indexOf(delim, open + 1)
        if (open < 0 || close < 0) return null
        return h.substring(open + 1, close)
    }

    /** the (a, b) tuple of the header's 'shape' entry */
    private fun shapeOf(h: String): IntArray? {
        val k1 = h.indexOf("'shape'")
        val k2 = h.indexOf("\"shape\"")
        val k = if (k1 >= 0) k1 else k2
        if (k < 0) return null
        val open = h.indexOf('(', k)
        if (open < 0) return null
        val close = h.indexOf(')', open + 1)
        if (close < 0) return null
        val parts = h.substring(open + 1, close).split(',')
            .mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 2) return null
        return intArrayOf(parts[0], parts[1])
    }
}
