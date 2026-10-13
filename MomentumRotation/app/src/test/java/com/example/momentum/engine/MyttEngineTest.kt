package com.example.momentum.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MyttEngineTest {

    @Test
    fun testCompareWithPythonResultsOnOctober8() {
        // 读取 Python 生成的测试样本数据
        val csvFile = File("../../mytt_selector/data/sample_bars_for_kotlin.csv")
        if (!csvFile.exists()) {
            println("CSV file not found, skipping: ${csvFile.absolutePath}")
            return
        }

        val lines = csvFile.readLines().drop(1)
        val grouped = lines.groupBy { it.split(",")[0] }

        val results = mutableMapOf<String, MyttEngine.MyttResult>()

        for ((symbol, rows) in grouped) {
            val opens = DoubleArray(rows.size)
            val closes = DoubleArray(rows.size)
            val highs = DoubleArray(rows.size)
            val lows = DoubleArray(rows.size)
            val volumes = DoubleArray(rows.size)

            rows.forEachIndexed { i, line ->
                val parts = line.split(",")
                opens[i] = parts[2].toDouble()
                closes[i] = parts[3].toDouble()
                highs[i] = parts[4].toDouble()
                lows[i] = parts[5].toDouble()
                volumes[i] = parts[6].toDouble()
            }

            val resList = MyttEngine.computeRecent(closes, opens, highs, lows, volumes, lookbackDays = 1)
            assertTrue("Should compute results for $symbol", resList.isNotEmpty())
            val latest = resList.last()
            results[symbol] = latest

            println("Kotlin Engine -> $symbol: wanshou=${latest.sigWanshou}, xlj4=${latest.sigXlj4}, signal=${latest.signal}, xlj4_7=${latest.xlj4_7}")
        }

        // 验证 10月8日 3 只双共振股票
        val lp = results["sh603906"] // 龙蟠科技
        assertNotNull(lp)
        assertTrue("龙蟠科技 万手单应为 true", lp!!.sigWanshou)
        assertTrue("龙蟠科技 寻龙诀4号应为 true", lp.sigXlj4)
        assertTrue("龙蟠科技 综合信号应为 true", lp.signal)

        val zy = results["sz000869"] // 张裕A
        assertNotNull(zy)
        assertTrue("张裕A 万手单应为 true", zy!!.sigWanshou)
        assertTrue("张裕A 寻龙诀4号应为 true", zy.sigXlj4)
        assertTrue("张裕A 综合信号应为 true", zy.signal)

        val lw = results["bj920627"] // 力王股份
        assertNotNull(lw)
        assertTrue("力王股份 万手单应为 true", lw!!.sigWanshou)
        assertTrue("力王股份 寻龙诀4号应为 true", lw.sigXlj4)
        assertTrue("力王股份 综合信号应为 true", lw.signal)

        // 验证 联环药业: 仅寻龙诀4号，万手单为 false，综合为 false
        val lh = results["sh600513"]
        assertNotNull(lh)
        assertFalse("联环药业 万手单应为 false", lh!!.sigWanshou)
        assertTrue("联环药业 寻龙诀4号应为 true", lh.sigXlj4)
        assertFalse("联环药业 综合信号应为 false", lh.signal)

        // 验证 必得科技: 仅万手单，寻龙诀4号为 false，综合为 false
        val bd = results["sh605298"]
        assertNotNull(bd)
        assertTrue("必得科技 万手单应为 true", bd!!.sigWanshou)
        assertFalse("必得科技 寻龙诀4号应为 false", bd.sigXlj4)
        assertFalse("必得科技 综合信号应为 false", bd.signal)
    }
}
