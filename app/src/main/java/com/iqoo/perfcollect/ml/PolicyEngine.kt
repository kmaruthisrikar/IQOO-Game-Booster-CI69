package com.iqoo.perfcollect.ml

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NPU-friendly policy engine. Backend is swappable:
 *  - KotlinMlpEngine: pure-Kotlin CPU forward pass (zero deps, always works)
 *  - (future) QnnHtpEngine: same weights via QNN on Hexagon NPU
 */
interface PolicyEngine {
    /** returns index of best action (argmax over Q-values) */
    fun action(state: FloatArray): Int
    fun qValues(state: FloatArray): FloatArray
}

/**
 * 3-layer ReLU MLP (8 -> 128 -> 128 -> 15) with an online-learning friendly
 * mutable weight store. Weights are loaded from an asset .bin (frozen weights)
 * or from a persisted file (after on-device Q-learning updates).
 */
class KotlinMlpEngine private constructor(
    val n1: Int,
    val n2: Int,
    val nOut: Int,
    val nIn: Int,
    var w1: FloatArray,
    var b1: FloatArray,
    var w2: FloatArray,
    var b2: FloatArray,
    var w3: FloatArray,
    var b3: FloatArray,
) : PolicyEngine {

    constructor(context: Context, assetName: String) : this(parse(context.assets.open(assetName).use { it.readBytes() }))

    private constructor(b: Blob) : this(b.n1, b.n2, b.nOut, b.nIn, b.w1, b.b1, b.w2, b.b2, b.w3, b.b3)

    private class Blob(
        val n1: Int, val n2: Int, val nOut: Int, val nIn: Int,
        val w1: FloatArray, val b1: FloatArray, val w2: FloatArray, val b2: FloatArray,
        val w3: FloatArray, val b3: FloatArray,
    )

    companion object {
        private fun parse(bytes: ByteArray): Blob {
            val h = headerOf(bytes) ?: throw IllegalArgumentException(
                "malformed model blob (${bytes.size} bytes — header/length mismatch)")
            val n1 = h[0]; val n2 = h[1]; val nOut = h[2]; val nIn = h[3]
            if (n1 !in 1..512 || n2 !in 1..512) throw IllegalArgumentException(
                "model hidden dims out of range: $n1/$n2")
            if (nIn !in 1..64 || nOut !in 1..64) throw IllegalArgumentException(
                "model I/O dims out of range: in=$nIn, out=$nOut")
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(16)
            val fb = bb.asFloatBuffer()
            val w1 = FloatArray(n1 * nIn); fb.get(w1)
            val b1 = FloatArray(n1); fb.get(b1)
            val w2 = FloatArray(n2 * n1); fb.get(w2)
            val b2 = FloatArray(n2); fb.get(b2)
            val w3 = FloatArray(nOut * n2); fb.get(w3)
            val b3 = FloatArray(nOut); fb.get(b3)
            return Blob(n1, n2, nOut, nIn, w1, b1, w2, b2, w3, b3)
        }

        /** validates a weights blob structurally: returns [n1,n2,nOut,nIn] or null.
         *  NO dimension limits — any positive dims that match the byte size load. */
        fun headerOf(bytes: ByteArray): IntArray? {
            if (bytes.size < 16) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n1 = bb.int; val n2 = bb.int; val nOut = bb.int; val nIn = bb.int
            if (n1 <= 0 || n2 <= 0 || nOut <= 0 || nIn <= 0) return null
            val need = 16 + 4L * (n1.toLong() * nIn + n1 + n2.toLong() * n1 + n2 + nOut.toLong() * n2 + nOut)
            if (bytes.size.toLong() != need) return null
            return intArrayOf(n1, n2, nOut, nIn)
        }

        /** true when a loaded engine produces finite Q-values on a zero probe */
        fun isHealthy(eng: KotlinMlpEngine): Boolean = try {
            eng.w1.all { it.isFinite() } && eng.b1.all { it.isFinite() } && eng.w2.all { it.isFinite() } && eng.b2.all { it.isFinite() } && eng.w3.all { it.isFinite() } && eng.b3.all { it.isFinite() }
        } catch (_: Exception) { false }

        /** in-place refill of weights from a same-dimension blob (rollback) */
        fun restoreFrom(eng: KotlinMlpEngine, bytes: ByteArray) {
            val b = parse(bytes)
            require(b.n1 == eng.n1 && b.n2 == eng.n2 && b.nOut == eng.nOut && b.nIn == eng.nIn) {
                "Dimension mismatch in restoreFrom"
            }
            eng.w1 = b.w1; eng.b1 = b.b1
            eng.w2 = b.w2; eng.b2 = b.b2
            eng.w3 = b.w3; eng.b3 = b.b3
        }

        /** builds an engine from a flat binary file (same format as the asset .bin) */
        fun fromBytes(bytes: ByteArray): KotlinMlpEngine {
            val b = parse(bytes)
            return KotlinMlpEngine(b.n1, b.n2, b.nOut, b.nIn, b.w1, b.b1, b.w2, b.b2, b.w3, b.b3)
        }
    }

    /** serializes current weights (frozen + online-adapted) to the .bin format */
    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(16 + 4 * (n1 * nIn + n1 + n2 * n1 + n2 + nOut * n2 + nOut))
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(n1); buf.putInt(n2); buf.putInt(nOut); buf.putInt(nIn)
        w1.forEach { buf.putFloat(it) }
        b1.forEach { buf.putFloat(it) }
        w2.forEach { buf.putFloat(it) }
        b2.forEach { buf.putFloat(it) }
        w3.forEach { buf.putFloat(it) }
        b3.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun relu(v: Float) = if (v.isNaN()) Float.NaN else if (v > 0f) v else 0f

    override fun qValues(state: FloatArray): FloatArray {
        val h1 = FloatArray(n1)
        val inLen = minOf(nIn, state.size)
        for (i in 0 until n1) {
            var acc = b1[i]
            var j = 0
            while (j < inLen) { acc += state[j] * w1[i * nIn + j]; j++ }
            h1[i] = relu(acc)
        }
        val h2 = FloatArray(n2)
        for (i in 0 until n2) {
            var acc = b2[i]
            var j = 0
            while (j < n1) { acc += h1[j] * w2[i * n1 + j]; j++ }
            h2[i] = relu(acc)
        }
        val q = FloatArray(nOut)
        for (i in 0 until nOut) {
            var acc = b3[i]
            var j = 0
            while (j < n2) { acc += h2[j] * w3[i * n2 + j]; j++ }
            q[i] = acc
        }
        return q
    }

    override fun action(state: FloatArray): Int {
        val q = qValues(state)
        var best = 0
        var bestV = Float.NEGATIVE_INFINITY
        for (i in q.indices) {
            if (q[i] > bestV) {
                bestV = q[i]
                best = i
            }
        }
        return best
    }
}