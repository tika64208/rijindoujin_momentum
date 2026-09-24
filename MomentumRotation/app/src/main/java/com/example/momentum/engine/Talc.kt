package com.example.momentum.engine

import kotlin.math.roundToInt

/**
 * 通达信函数库的 Kotlin 翻译(talc.py)。
 *
 * 所有函数以 DoubleArray 或 BooleanArray 为输入, 返回等长数组。
 * 不依赖第三方数值库, 只用标准库(对标 mytt_selector/talc.py 的 numpy/pandas 实现)。
 *
 * 约定:
 *  - 所有 DoubleArray 长度一致
 *  - BARSLAST/FILTER 等有状态函数对 NaN 的处理与 Python 版一致
 *  - NaN: Double.NaN
 *  - true=1.0, false=0.0 (CONVERT_BOOL)
 */
object Talc {

    /** 检查值是否"为真"(非 0 且非 NaN)。 */
    private fun isTrue(v: Double): Boolean = v != 0.0 && !v.isNaN()
    /** 检查值是否"为真"(布尔版)。 */
    private fun isTrue(b: Boolean?): Boolean = b == true

    // ---- 简单窗口函数 ----

    /** MA: N 日均线(含当前)。 */
    fun ma(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size)
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i]
            if (i >= n) sum -= x[i - n]
            val denom = if (i + 1 < n) (i + 1).toDouble() else n.toDouble()
            out[i] = sum / denom
        }
        return out
    }

    /** REF: n 周期前的值(n=0 即当前)。 */
    fun ref(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        for (i in x.indices) {
            if (i - n >= 0) out[i] = x[i - n]
        }
        return out
    }
    fun ref(b: BooleanArray, n: Int): BooleanArray {
        val out = BooleanArray(b.size)
        for (i in b.indices) {
            if (i - n >= 0) out[i] = b[i - n] else out[i] = false
        }
        return out
    }

    /** HHV: N 周期最高值(含当前)。n 可为 Int 或 IntArray(动态窗口)。 */
    fun hhv(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            val lo = (i - n + 1).coerceAtLeast(0)
            var mx = Double.NaN
            for (j in lo..i) { val v = x[j]; if (!v.isNaN() && (mx.isNaN() || v > mx)) mx = v }
            out[i] = mx
        }
        return out
    }

    /** LLV: N 周期最低值(含当前)。 */
    fun llv(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            val lo = (i - n + 1).coerceAtLeast(0)
            var mn = Double.NaN
            for (j in lo..i) { val v = x[j]; if (!v.isNaN() && (mn.isNaN() || v < mn)) mn = v }
            out[i] = mn
        }
        return out
    }

    // ---- 有状态函数 ----

    /** BARSLAST: 距上次为真的周期数。当前为真 → 0；从未为真 → NaN。 */
    fun barslast(cond: BooleanArray): DoubleArray {
        val out = DoubleArray(cond.size)
        var lastTrue = -1
        for (i in cond.indices) {
            if (isTrue(cond[i])) {
                out[i] = 0.0
                lastTrue = i
            } else {
                out[i] = if (lastTrue >= 0) (i - lastTrue).toDouble() else Double.NaN
            }
        }
        return out
    }

    /** FILTER: 信号过滤, 发生后 n 周期内屏蔽后续信号。 */
    fun filter(cond: BooleanArray, n: Int): BooleanArray {
        val out = BooleanArray(cond.size)
        var suppressedUntil = -1
        for (i in cond.indices) {
            if (isTrue(cond[i]) && i > suppressedUntil) {
                out[i] = true
                suppressedUntil = i + n
            }
        }
        return out
    }

    /** COUNT: 最近 n 周期内为真的次数(含当前)。 */
    fun count(cond: BooleanArray, n: Int): DoubleArray {
        val out = DoubleArray(cond.size)
        var cnt = 0.0
        for (i in cond.indices) {
            if (isTrue(cond[i])) cnt += 1.0
            if (i >= n && isTrue(cond[i - n])) cnt -= 1.0
            out[i] = cnt
        }
        return out
    }

    /** CROSS: a 上穿 b, a[i-1]<=b[i-1] && a[i]>b[i]。 */
    fun cross(a: DoubleArray, b: DoubleArray): BooleanArray {
        val out = BooleanArray(a.size)
        for (i in 1 until a.size) {
            if (a[i-1] <= b[i-1] && a[i] > b[i]) out[i] = true
        }
        return out
    }

    /** DMA: 动态移动平均。Y[i] = a[i]*X[i] + (1-a[i])*Y[i-1], 初始 Y[0]=X[0]。 */
    fun dma(x: DoubleArray, a: DoubleArray): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (x.isEmpty()) return out
        out[0] = x[0]
        for (i in 1 until x.size) {
            val ai = a[i].coerceIn(0.0, 1.0)
            out[i] = ai * x[i] + (1.0 - ai) * out[i - 1]
        }
        return out
    }

    /** CONVERT_BOOL: 非 0 且非 NaN → 1.0，否则 0.0。 */
    fun convertBool(b: BooleanArray): DoubleArray {
        return DoubleArray(b.size) { if (isTrue(b[it])) 1.0 else 0.0 }
    }

    // ---- 动态 REF（对标 _ref_dyn） ----

    /**
     * 动态 REF: 对每个位置 i，取 x[i - n[i]]。n 为回溯周期数(向下取整)。
     * 越界或 NaN → NaN。
     */
    fun refDyn(x: DoubleArray, n: DoubleArray): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        for (i in x.indices) {
            val k = n[i]
            if (k.isNaN()) continue
            val j = i - k.roundToInt()
            if (j in x.indices) out[i] = x[j]
        }
        return out
    }
}