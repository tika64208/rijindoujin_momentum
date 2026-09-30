package com.mytt.selector

/**
 * Talc.kt —— 通达信函数库的 Kotlin 移植
 * =====================================
 * 对应 Python 版 talc.py。所有函数输入为 double 数组(按日期升序), 返回同长度数组。
 * 数组中的 NaN 表示"无数据/无效"(如 REF 开头、BARSLAST 从未触发的部分)。
 */
object Talc {

    /** N 日均线，包含当前。n<1 时返回原值。 */
    fun MA(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            if (n < 1) { out[i] = x[i]; continue }
            var s = 0.0; var c = 0
            val lo = (i - n + 1).coerceAtLeast(0)
            for (j in lo..i) { if (!x[j].isNaN()) { s += x[j]; c++ } }
            out[i] = if (c > 0) s / c else Double.NaN
        }
        return out
    }

    /** n 个周期前的值(不含空窗则 NaN)。 */
    fun REF(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            val j = i - n
            out[i] = if (j >= 0 && j < x.size) x[j] else Double.NaN
        }
        return out
    }

    /** N 周期最高，含当前。n 可为定数或数组(动态窗口, 元素为窗口长度)。 */
    fun HHV(x: DoubleArray, n: Any): DoubleArray {
        if (n is Int)
            return rollingWindow(x, DoubleArray(x.size) { n.toDouble() }, ::maxOf)
        return rollingWindow(x, n as DoubleArray, ::maxOf)
    }

    /** N 周期最低，含当前。n 可为定数或数组(动态窗口)。 */
    fun LLV(x: DoubleArray, n: Any): DoubleArray {
        if (n is Int)
            return rollingWindow(x, DoubleArray(x.size) { n.toDouble() }, ::minOf)
        return rollingWindow(x, n as DoubleArray, ::minOf)
    }

    private fun rollingWindow(x: DoubleArray, n: DoubleArray, fn: (Double, Double) -> Double): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            val wRaw = n[i]
            if (wRaw.isNaN() || wRaw < 1) {
                out[i] = if (x[i].isNaN()) Double.NaN else x[i]
                continue
            }
            val w = wRaw.toInt().coerceAtLeast(1)
            val lo = (i - w + 1).coerceAtLeast(0)
            var acc = Double.NaN
            for (j in lo..i) {
                if (x[j].isNaN()) continue
                acc = if (acc.isNaN()) x[j] else fn(acc, x[j])
            }
            out[i] = acc
        }
        return out
    }

    /**
     * 距最近一次 cond 为真(非0) 的周期数。
     * 当前为真→0; 之前某处为真→间距; 从未为真→NaN。
     */
    fun BARSLAST(cond: BooleanArray): DoubleArray {
        val out = DoubleArray(cond.size)
        var lastTrue = -1
        for (i in cond.indices) {
            if (cond[i]) { out[i] = 0.0; lastTrue = i }
            else out[i] = if (lastTrue >= 0) (i - lastTrue).toDouble() else Double.NaN
        }
        return out
    }

    /**
     * 信号过滤: cond 为真时输出信号, 之后 n 个周期内再为真则屏蔽。
     */
    fun FILTER(cond: BooleanArray, n: Int): BooleanArray {
        val out = BooleanArray(cond.size)
        var suppressUntil = -1
        for (i in cond.indices) {
            if (cond[i] && i > suppressUntil) { out[i] = true; suppressUntil = i + n }
        }
        return out
    }

    /** 最近 n 个周期(含当前)内 cond 为真的次数(不含窗口首部的 NaN 前段)。 */
    fun COUNT(cond: BooleanArray, n: Int): DoubleArray {
        val out = DoubleArray(cond.size)
        for (i in cond.indices) {
            var c = 0
            val lo = (i - n + 1).coerceAtLeast(0)
            for (j in lo..i) if (cond[j]) c++
            out[i] = c.toDouble()
        }
        return out
    }

    /** A 上穿 B: 上周期 A<=B 且 本周期 A>B。布尔。 */
    fun CROSS(a: DoubleArray, b: DoubleArray): BooleanArray {
        val out = BooleanArray(a.size)
        for (i in a.indices) {
            if (i == 0) { out[i] = false; continue }
            val pa = a[i - 1]; val pb = b[i - 1]; val ca = a[i]; val cb = b[i]
            out[i] = !pa.isNaN() && !pb.isNaN() && !ca.isNaN() && !cb.isNaN()
                    && pa <= pb && ca > cb
        }
        return out
    }

    /** 布尔/数值数组转 0/1。 */
    fun CONVERT_BOOL(s: BooleanArray): DoubleArray =
        DoubleArray(s.size) { if (s[it]) 1.0 else 0.0 }

    /**
     * 动态 REF: 对位置 i 取 x[i - n[i]] 处的值。n 为各位置回溯周期。
     */
    fun refDyn(x: DoubleArray, n: DoubleArray): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            val k = n[i]
            if (k.isNaN()) { out[i] = Double.NaN; continue }
            val j = i - k.roundToInt()
            out[i] = if (j in x.indices) x[j] else Double.NaN
        }
        return out
    }

    private fun Double.roundToInt(): Int = Math.round(this).toInt()
}
