package com.example.momentum.engine

/**
 * 万手单 + 寻龙诀4号 选股公式的 Kotlin 翻译(mytt.py)。
 *
 * 输入: 日线数据数组(close, open, high, low, volume), 按日期升序。
 * 输出: 最后一根 K 线的信号结果。
 */
object MyttEngine {

    data class MyttResult(
        val sigWanshou: Boolean,      // 万手单信号
        val sigXlj4: Boolean,         // 寻龙诀4号信号
        val signal: Boolean,          // 双信号(万手单 AND 寻龙诀4号)
        val wsd25: Boolean,           // 万手单中间条件 WSD_25
        val xlj4_7: Double            // 距上次寻龙诀信号的周期数
    )

    /**
     * 计算单只股票所有天的 MYTT 信号(返回最后 5 天的结果)。
     * @param close  收盘价序列(按时间升序)
     * @param open   开盘价序列
     * @param high   最高价序列
     * @param low    最低价序列
     * @param volume 成交量序列
     * @param lookbackDays 返回最近 N 天的信号, 默认 5
     * @return 最近 N 天的信号列表(按日期升序, 最后一条 === latest)
     */
    fun computeRecent(
        close: DoubleArray,
        open: DoubleArray,
        high: DoubleArray,
        low: DoubleArray,
        volume: DoubleArray,
        lookbackDays: Int = 5
    ): List<MyttResult> {
        if (close.size < 300) return emptyList()
        val (sigWanshou, sigXlj4, signal, wsD25, xlj4_7) = computeFull(close, open, high, low, volume)
        val start = (close.size - lookbackDays).coerceAtLeast(0)
        val results = mutableListOf<MyttResult>()
        for (i in start until close.size) {
            results.add(MyttResult(
                sigWanshou = sigWanshou[i],
                sigXlj4 = sigXlj4[i],
                signal = signal[i],
                wsd25 = wsD25[i],
                xlj4_7 = xlj4_7[i]
            ))
        }
        return results
    }

    /**
     * 内部: 返回完整序列(BooleanArray), 用于取最后 N 天。
     */
    private fun computeFull(
        c: DoubleArray, o: DoubleArray, h: DoubleArray,
        l: DoubleArray, v: DoubleArray
    ): Quintet {
        val n = c.size
        // ===== 万手单 (WSD) =====
        val wsd1 = Talc.ma(c, 10)
        val wsd2 = Talc.ma(c, 54)
        // WSD_3: (REF(C,3)-C)/REF(C,3)*100 > 5
        val refC3 = Talc.ref(c, 3)
        val wsd3 = BooleanArray(c.size) { i ->
            !refC3[i].isNaN() && c[i].isFinite() && (refC3[i] - c[i]) / refC3[i] * 100.0 > 5.0
        }
        val wsd4 = Talc.filter(wsd3, 10)
        val wsd5 = Talc.barslast(wsd4)
        // WSD_5 + 2, +1, +0 的动态 ref
        val wsd5p2 = DoubleArray(wsd5.size) { if (wsd5[it].isNaN()) Double.NaN else wsd5[it] + 2.0 }
        val wsd5p1 = DoubleArray(wsd5.size) { if (wsd5[it].isNaN()) Double.NaN else wsd5[it] + 1.0 }
        val wsd6 = Talc.refDyn(h, wsd5p2)
        val wsd7 = Talc.refDyn(h, wsd5p1)
        val wsd8 = Talc.refDyn(h, wsd5)

        val wsd10 = DoubleArray(c.size) { i ->
            maxOf(
                if (wsd6[i].isNaN()) Double.NEGATIVE_INFINITY else wsd6[i],
                if (wsd7[i].isNaN()) Double.NEGATIVE_INFINITY else wsd7[i],
                if (wsd8[i].isNaN()) Double.NEGATIVE_INFINITY else wsd8[i]
            ).let { if (it == Double.NEGATIVE_INFINITY) Double.NaN else it }
        }

        val refC1 = Talc.ref(c, 1)
        // WSD_11: (C-REF(C,1))/REF(C,1)*100 > 5
        val wsd11 = BooleanArray(c.size) { i ->
            !refC1[i].isNaN() && refC1[i] != 0.0 && (c[i] - refC1[i]) / refC1[i] * 100.0 > 5.0
        }
        // WSD_12: WSD_5 < 150
        val wsd12 = BooleanArray(wsd5.size) { !wsd5[it].isNaN() && wsd5[it] < 150.0 }
        // WSD_13: (O - WSD_10)/WSD_10*100 < 30
        val wsd13 = BooleanArray(c.size) { i ->
            !wsd10[i].isNaN() && wsd10[i] != 0.0 && (o[i] - wsd10[i]) / wsd10[i] * 100.0 < 30.0
        }
        // WSD_14: (C - LLV(L,WSD_5))/LLV(L,WSD_5)*100 < 50
        val wsd14 = BooleanArray(c.size) { i ->
            val w = wsd5[i]
            if (w.isNaN() || w < 1.0) return@BooleanArray false
            val lo = (i - w.toInt() + 1).coerceAtLeast(0)
            var mn = Double.MAX_VALUE
            for (j in lo..i) if (!l[j].isNaN() && l[j] < mn) mn = l[j]
            mn > 0.0 && !mn.isInfinite() && (c[i] - mn) / mn * 100.0 < 50.0
        }
        // WSD_15: (C - REF(O,5))/REF(O,5)*100 < 30
        val refO5 = Talc.ref(o, 5)
        val wsd15 = BooleanArray(c.size) { i ->
            !refO5[i].isNaN() && refO5[i] != 0.0 && (c[i] - refO5[i]) / refO5[i] * 100.0 < 30.0
        }
        // WSD_16: V/MA(V,5) < 3.5
        val maV5 = Talc.ma(v, 5)
        val wsd16 = BooleanArray(c.size) { i -> maV5[i] > 0.0 && v[i] / maV5[i] < 3.5 }
        // WSD_17: (C - REF(C,89))/REF(C,89)*100 < 80
        val refC89 = Talc.ref(c, 89)
        val wsd17 = BooleanArray(c.size) { i ->
            !refC89[i].isNaN() && refC89[i] != 0.0 && (c[i] - refC89[i]) / refC89[i] * 100.0 < 80.0
        }
        val wsd18 = BooleanArray(c.size) { wsd11[it] && wsd12[it] && wsd13[it] && wsd14[it] && wsd15[it] && wsd16[it] && wsd17[it] }
        val wsd19 = Talc.filter(wsd18, 15)
        // WSD_20/21
        val wsd20 = BooleanArray(c.size) { i -> wsd2[i] != 0.0 && (c[i] - wsd2[i]) / wsd2[i] < 0.1 }
        val wsd21 = BooleanArray(c.size) { i -> wsd1[i] != 0.0 && (c[i] - wsd1[i]) / wsd1[i] < 0.3 }
        val wsd22 = DoubleArray(c.size) { i -> if (wsd20[i] && wsd21[i]) 0.2 else 0.0 }
        // WSD_23
        val refWsd22_1 = Talc.ref(wsd22, 1)
        val cntWsd22 = Talc.count(BooleanArray(wsd22.size) { wsd22[it] == 0.2 }, 10)
        val refCntWsd22_1 = Talc.ref(cntWsd22, 1)
        val wsd23 = DoubleArray(c.size) { i ->
            if (wsd22[i] == 0.0 && refWsd22_1[i] == 0.2 && refCntWsd22_1[i] == 10.0) -0.1 else 0.0
        }
        val wsd24 = BooleanArray(c.size) { wsd23[it] == -0.1 }
        val wsD25 = BooleanArray(c.size) { wsd19[it] || wsd24[it] }

        // WSD_27: C/REF(C,1)>1.05 AND C/O>1.05
        val wsd27 = BooleanArray(c.size) { i ->
            !refC1[i].isNaN() && refC1[i] != 0.0 && c[i]/refC1[i] > 1.05 && o[i] != 0.0 && c[i]/o[i] > 1.05
        }
        // WSD_28
        val refV1 = Talc.ref(v, 1)
        val vRatio = DoubleArray(v.size) { i -> if (!refV1[i].isNaN() && refV1[i] > 0.0) v[i]/refV1[i] else 0.0 }
        val refH1 = Talc.ref(h, 1)
        val wsd28 = BooleanArray(c.size) { i ->
            (vRatio[i] > 1.2 && c[i] > o[i]) ||
            (l[i] > refH1[i] && o[i] > c[i] && vRatio[i] > 1.2)
        }
        // sig_wanshou
        val sigWanshou = BooleanArray(c.size) { i ->
            wsD25[i] && !refC1[i].isNaN() && refC1[i] != 0.0 && c[i]/refC1[i] > 1.095 && wsd28[i] && wsd27[i]
        }

        // ===== 寻龙诀4号 (XLJ4) =====
        val xlj1 = DoubleArray(c.size) { i ->
            if (!refC1[i].isNaN() && refC1[i] != 0.0) (c[i]-refC1[i])/refC1[i]*100.0 else 0.0
        }
        // XLJ4_2: REF(C,10)==HHV(C,21)
        val refC10 = Talc.ref(c, 10)
        val hhv21 = Talc.hhv(c, 21)
        val xlj2 = BooleanArray(c.size) { i -> !refC10[i].isNaN() && !hhv21[i].isNaN() && refC10[i] == hhv21[i] }
        val xlj3 = Talc.filter(xlj2, 10)
        val xlj4 = Talc.filter(xlj3, 11)
        val xlj5 = Talc.filter(xlj4, 10)
        val xlj7 = Talc.barslast(xlj5)
        val xlj6 = Talc.refDyn(c, xlj7)
        // XLJ4_8: (HHV(H,XLJ4_7)-LLV(L,XLJ4_7))/LLV(L,XLJ4_7)*100
        val xlj8 = DoubleArray(c.size) { i ->
            val w = xlj7[i]
            if (w.isNaN() || w < 1.0) return@DoubleArray Double.NaN
            val lo = (i - w.toInt() + 1).coerceAtLeast(0)
            var mx = Double.NaN; var mn = Double.NaN
            for (j in lo..i) {
                val hv = h[j]; if (!hv.isNaN() && (mx.isNaN() || hv > mx)) mx = hv
                val lv = l[j]; if (!lv.isNaN() && (mn.isNaN() || lv < mn)) mn = lv
            }
            if (mn.isNaN() || mn == 0.0 || mx.isNaN()) return@DoubleArray Double.NaN
            (mx - mn) / mn * 100.0
        }
        val refXlj7_1 = Talc.ref(xlj7, 1)
        val refXlj8_1 = Talc.ref(xlj8, 1)
        val refV2 = Talc.ref(v, 2)
        val crs = Talc.cross(c, xlj6)  // 上穿判断: REF(C,1)<=XLJ4_6 AND C>XLJ4_6
        val sigXlj4 = BooleanArray(n) { i ->
            (!refXlj7_1[i].isNaN() && refXlj7_1[i] > 10.0) &&
            (!refXlj8_1[i].isNaN() && refXlj8_1[i] < 100.0) &&
            crs[i] &&
            (v[i] >= refV1[i] * 2.0 || (i >= 2 && v[i] >= (if (refV2[i].isNaN()) 0.0 else refV2[i]) * 2.0)) &&
            xlj1[i] > 2.0 &&
            c[i] < 50.0
        }

        // ===== 最终信号 =====
        val signal = BooleanArray(n) { sigWanshou[it] && sigXlj4[it] }

        return Quintet(sigWanshou, sigXlj4, signal, wsD25, xlj7)
    }
}

/** 内部五元组，用于 computeFull 返回完整序列。 */
private data class Quintet(
    val sigWanshou: BooleanArray,
    val sigXlj4: BooleanArray,
    val signal: BooleanArray,
    val wsd25: BooleanArray,
    val xlj4_7: DoubleArray
)