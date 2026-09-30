package com.mytt.selector

/**
 * Mytt.kt —— 万手单 + 寻龙诀4号 的 Kotlin 实现
 * =============================================
 * 对应 Python 版 mytt.py。输入日线 OHLCV(按日期升序), 计算每个周期的信号。
 */
object Mytt {

    /** 单只股票的日线数据(按日期升序)。 */
    data class Kline(
        val date: Array<String>,   // "2026-09-23"
        val open: DoubleArray,
        val high: DoubleArray,
        val low: DoubleArray,
        val close: DoubleArray,
        val volume: DoubleArray,
        val amount: DoubleArray,
    )

    /** 计算结果: 最后一根K线的各信号。 */
    data class Result(
        val code: String,
        val name: String,
        val date: String,
        val close: Double,
        val chgPct: Double,
        val sigWanshou: Boolean,
        val sigXlj4: Boolean,
        val signal: Boolean,
        val wsd25: Boolean,
        val xlj47: Double,
        val bars: Int,          // 参与计算的K线数
    )

    /** 对整段日线计算, 返回最后一根K线的信号。 */
    fun compute(k: Kline, code: String = "", name: String = ""): Result {
        val close = k.close
        val open = k.open
        val high = k.high
        val low = k.low
        val vol = k.volume

        // ---------- 万手单 (WSD) ----------
        val WSD_1 = Talc.MA(close, 10)
        val WSD_2 = Talc.MA(close, 54)

        val refC3 = Talc.REF(close, 3)
        val WSD_3 = BooleanArray(close.size) { i ->
            !refC3[i].isNaN() && (refC3[i] - close[i]) / refC3[i] * 100 > 5
        }
        val WSD_4 = Talc.FILTER(WSD_3, 10)
        val WSD_5 = Talc.BARSLAST(WSD_4)

        val WSD_6 = Talc.refDyn(high, addConst(WSD_5, 2))
        val WSD_7 = Talc.refDyn(high, addConst(WSD_5, 1))
        val WSD_8 = Talc.refDyn(high, WSD_5)
        val WSD_9 = elementWise(WSD_6, WSD_7) { a, b -> maxOf(a, b) }
        val WSD_10 = elementWise(WSD_9, WSD_8) { a, b -> maxOf(a, b) }

        val refC1 = Talc.REF(close, 1)
        val WSD_11 = BooleanArray(close.size) { i ->
            !refC1[i].isNaN() && (close[i] - refC1[i]) / refC1[i] * 100 > 5
        }
        val WSD_12 = BooleanArray(close.size) { i -> WSD_5[i] < 150.0 }
        val WSD_13 = BooleanArray(close.size) { i ->
            !WSD_10[i].isNaN() && (open[i] - WSD_10[i]) / WSD_10[i] * 100 < 30
        }
        val LLV_low_WSD5 = Talc.LLV(low, WSD_5)
        val WSD_14 = BooleanArray(close.size) { i ->
            !LLV_low_WSD5[i].isNaN() && (close[i] - LLV_low_WSD5[i]) / LLV_low_WSD5[i] * 100 < 50
        }
        val refO5 = Talc.REF(open, 5)
        val WSD_15 = BooleanArray(close.size) { i ->
            !refO5[i].isNaN() && (close[i] - refO5[i]) / refO5[i] * 100 < 30
        }
        val MA_vol5 = Talc.MA(vol, 5)
        val WSD_16 = BooleanArray(close.size) { i ->
            MA_vol5[i] != 0.0 && vol[i] / MA_vol5[i] < 3.5
        }
        val refC89 = Talc.REF(close, 89)
        val WSD_17 = BooleanArray(close.size) { i ->
            !refC89[i].isNaN() && (close[i] - refC89[i]) / refC89[i] * 100 < 80
        }
        val WSD_18 = BooleanArray(close.size) { i ->
            WSD_11[i] && WSD_12[i] && WSD_13[i] && WSD_14[i] && WSD_15[i] && WSD_16[i] && WSD_17[i]
        }
        val WSD_19 = Talc.FILTER(WSD_18, 15)
        val WSD_20 = BooleanArray(close.size) { i ->
            WSD_2[i] != 0.0 && (close[i] - WSD_2[i]) / WSD_2[i] < 0.1
        }
        val WSD_21 = BooleanArray(close.size) { i ->
            WSD_1[i] != 0.0 && (close[i] - WSD_1[i]) / WSD_1[i] < 0.3
        }
        val WSD_22 = DoubleArray(close.size) { i ->
            if (WSD_20[i] && WSD_21[i]) 0.2 else 0.0
        }

        val cnt22 = Talc.COUNT(BooleanArray(close.size) { i -> WSD_22[i] == 0.2 }, 10)
        val refWSD22_1 = Talc.REF(WSD_22, 1)
        val refCnt22 = Talc.REF(cnt22, 1)
        val WSD_23 = DoubleArray(close.size) { i ->
            val cond = (WSD_22[i] == 0.0) && (refWSD22_1[i] == 0.2) &&
                    !refCnt22[i].isNaN() && (refCnt22[i] == 10.0)
            if (cond) -0.1 else 0.0
        }
        val WSD_24 = BooleanArray(close.size) { i -> WSD_23[i] == -0.1 }
        val WSD_25 = BooleanArray(close.size) { i -> WSD_19[i] || WSD_24[i] }

        // WSD_26 死代码, 跳过
        // WSD_27: DYNAINFO(7) 用当日收盘价替代
        val WSD_27 = BooleanArray(close.size) { i ->
            i > 0 && !refC1[i].isNaN() && (close[i] / refC1[i] > 1.05) && (close[i] / open[i] > 1.05)
        }
        val refVol1 = Talc.REF(vol, 1)
        val volRatio = DoubleArray(close.size) { i ->
            if (!refVol1[i].isNaN() && refVol1[i] != 0.0) vol[i] / refVol1[i] else Double.NaN
        }
        val WSD_28 = BooleanArray(close.size) { i ->
            val a = volRatio[i] > 1.2 && (close[i] > open[i])
            val b = (low[i] > refHigh1(high, i)) && (open[i] > close[i]) && volRatio[i] > 1.2
            a || b
        }

        val sigWanshou = BooleanArray(close.size) { i ->
            i > 0 && WSD_25[i] && !refC1[i].isNaN() && (close[i] / refC1[i] > 1.095) &&
                    WSD_28[i] && WSD_27[i]
        }

        // ---------- 寻龙诀4号 (XLJ4) ----------
        val XLJ4_1 = DoubleArray(close.size) { i ->
            if (!refC1[i].isNaN() && refC1[i] != 0.0) (close[i] - refC1[i]) / refC1[i] * 100 else Double.NaN
        }
        val HHV21 = Talc.HHV(close, 21)
        val refC10 = Talc.REF(close, 10)
        val XLJ4_2 = BooleanArray(close.size) { i ->
            !refC10[i].isNaN() && !HHV21[i].isNaN() && refC10[i] == HHV21[i]
        }
        val XLJ4_3 = Talc.FILTER(XLJ4_2, 10)
        val XLJ4_4 = Talc.FILTER(XLJ4_3, 11)
        val XLJ4_5 = Talc.FILTER(XLJ4_4, 10)
        val XLJ4_7 = Talc.BARSLAST(XLJ4_5)

        val XLJ4_6 = Talc.refDyn(close, XLJ4_7)
        val HHV_high_X7 = Talc.HHV(high, XLJ4_7)
        val LLV_low_X7 = Talc.LLV(low, XLJ4_7)
        val XLJ4_8 = DoubleArray(close.size) { i ->
            if (!LLV_low_X7[i].isNaN() && LLV_low_X7[i] != 0.0)
                (HHV_high_X7[i] - LLV_low_X7[i]) / LLV_low_X7[i] * 100
            else Double.NaN
        }

        val refX7_1 = Talc.REF(XLJ4_7, 1)
        val refX8_1 = Talc.REF(XLJ4_8, 1)
        val refVol2 = Talc.REF(vol, 2)
        val crossCloseX6 = Talc.CROSS(close, XLJ4_6)
        val sigXlj4 = BooleanArray(close.size) { i ->
            i > 0 &&
                    refX7_1[i] > 10.0 &&
                    !refX8_1[i].isNaN() && refX8_1[i] < 100.0 &&
                    crossCloseX6[i] &&
                    (vol[i] >= refVol1[i] * 2 || vol[i] >= refVol2[i] * 2) &&
                    !XLJ4_1[i].isNaN() && XLJ4_1[i] > 2.0 &&
                    close[i] < 50.0
        }

        val signal = BooleanArray(close.size) { i -> sigWanshou[i] && sigXlj4[i] }

        // 取最后一根
        val n = close.size - 1
        val chg = if (n > 0 && !refC1[n].isNaN() && refC1[n] != 0.0)
            (close[n] / refC1[n] - 1) * 100 else Double.NaN
        return Result(
            code = code, name = name,
            date = if (k.date.isNotEmpty()) k.date[n] else "",
            close = close[n],
            chgPct = if (chg.isNaN()) 0.0 else chg,
            sigWanshou = sigWanshou[n],
            sigXlj4 = sigXlj4[n],
            signal = signal[n],
            wsd25 = WSD_25[n],
            xlj47 = XLJ4_7[n],
            bars = close.size,
        )
    }

    private fun refHigh1(high: DoubleArray, i: Int): Double =
        if (i > 0) high[i - 1] else Double.NaN

    private fun addConst(a: DoubleArray, c: Int): DoubleArray =
        DoubleArray(a.size) { if (a[it].isNaN()) Double.NaN else a[it] + c }

    private fun elementWise(a: DoubleArray, b: DoubleArray, fn: (Double, Double) -> Double): DoubleArray {
        val out = DoubleArray(a.size)
        for (i in a.indices) {
            out[i] = if (a[i].isNaN() || b[i].isNaN()) Double.NaN else fn(a[i], b[i])
        }
        return out
    }
}