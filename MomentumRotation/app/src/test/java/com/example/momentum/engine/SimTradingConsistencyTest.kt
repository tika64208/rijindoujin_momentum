package com.example.momentum.engine

import com.example.momentum.data.BidSignalSnapshots
import com.example.momentum.data.SimTradingDataRepository
import com.example.momentum.data.db.BidDataEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class SimTradingConsistencyTest {
    private fun rows(): List<BidDataEntity> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("sim_bid_20261105.json")).bufferedReader().use { it.readText() }
        val all = JSONArray(text)
        return (0 until all.length()).map { i -> all.getJSONObject(i).let { o -> BidDataEntity(
            code = o.getString("code"), date = o.getString("date"), name = o.getString("name"), latestPrice = o.getDouble("latestPrice"),
            realChange = o.getDouble("realChange"), openPrice = o.getDouble("openPrice"), volume = o.getInt("volume"),
            amount = o.getDouble("amount"), turnover = o.getDouble("turnover"), circMv = o.getDouble("circMv"),
            bidChange = o.getDouble("bidChange"), warnFlag = o.getInt("warnFlag"), industry = o.getString("industry"),
            conceptQuick = o.getString("conceptQuick"), probability = o.getInt("probability"), confidence = o.getInt("confidence"),
            score = o.getInt("score"), bidTurnover = o.getDouble("bidTurnover"), entityChange = o.getDouble("entityChange"),
            warnText = o.getString("warnText"), reason = o.getString("reason"), savedAt = o.getString("savedAt")) } }
    }
    private val config = BidStrategy.BidConfig.HIGH_WIN_RATE
    private val fee = SimTradingFeeConfig(enableSlippage = false)
    private val capital = 1_049_726.8288000003

    @Test fun `复现手机7只今日结算与回测逐笔一致且保持43689百分比`() {
        val source = rows()
        val candidates = BidCandidateSelector.select(source, config)
        assertEquals(7, candidates.size)
        assertFalse(candidates.any { it.code in setOf("688516", "300291") })
        val today = SimTradingEngine.executeSettle(SimTradingEngine.executeBuy(capital, "2026-11-05", candidates, fee),
            candidates.associate { it.code to it.latestPrice }, fee)
        val backtest = SimTradingEngine.runBacktest(capital, mapOf("2026-11-05" to source), config, fee).todayRecord!!
        assertEquals(4.368915601826328, today.returnPct, 1e-12)
        assertEquals(45_861.679199999955, today.profit, 1e-8)
        assertEquals(today, backtest)
        assertEquals(today.orders.associateBy { it.code }, backtest.orders.associateBy { it.code })
    }

    @Test fun `盘中量额涨幅改变不影响保存评分与选股名单`() {
        val source = rows()
        val changed = source.map { it.copy(volume = Int.MAX_VALUE, amount = 9e12, turnover = 99.0, realChange = 99.0) }
        assertEquals(BidCandidateSelector.select(source, config).map { it.code }, BidCandidateSelector.select(changed, config).map { it.code })
        assertEquals(34, changed.first { it.code == "688516" }.score)
    }

    @Test fun `保留旧分数但不将盘中成交量冒充早盘输入`() {
        val old = rows().first()
        val snapshot = BidSignalSnapshots.legacy(old)
        assertEquals(old.score, snapshot.signal.score)
        assertEquals(old.probability, snapshot.signal.probability)
        assertEquals(0, snapshot.signal.volume)
        assertEquals(0.0, snapshot.signal.amount, 0.0)
        assertEquals("", snapshot.rawInputJson)
        assertEquals("LEGACY_EARLY", snapshot.source)
        assertEquals("UNVERIFIED", BidSignalSnapshots.legacy(old.copy(savedAt = "14:00:00")).source)
    }

    @Test fun `异动标志4使用统一规则不会被位运算误剔除`() {
        val stock = rows().first().copy(warnFlag = 4, score = 40, probability = 80, confidence = 80)
        assertTrue(BidCandidateSelector.matches(stock, config))
        assertFalse(BidCandidateSelector.matches(stock.copy(warnFlag = 5), config))
        assertFalse(BidCandidateSelector.matches(stock.copy(name = "ST测试"), config))
    }

    @Test fun `缺失开盘价不允许以盘中最新价模拟买入`() {
        val stock = rows().first().copy(openPrice = 0.0)
        assertFalse(BidCandidateSelector.matches(stock, config))
        try { SimTradingEngine.executeBuy(capital, stock.date, listOf(stock), fee); fail("应拒绝") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `买入手续费包含在预算内不会产生负现金`() {
        val stock = rows().first().copy(openPrice = 10.0, latestPrice = 10.0)
        val bought = SimTradingEngine.executeBuy(100_000.0, stock.date, listOf(stock), fee)
        assertTrue(bought.orders.sumOf { it.actualCost + it.buyFee } <= 100_000.0)
    }

    @Test fun `持仓按买入时费用配置结算且滑点逐次结算不漂移`() {
        val stock = rows().first()
        val original = SimTradingFeeConfig()
        val bought = SimTradingEngine.executeBuy(capital, stock.date, listOf(stock), original)
        val settled = SimTradingEngine.executeSettle(bought, mapOf(stock.code to 40.0), SimTradingFeeConfig(enableFee = false, enableSlippage = false))
        assertTrue(settled.orders.single().sellFee > 0)
        assertEquals(40.0 * .999, settled.orders.single().sellPrice, 1e-12)
        assertEquals(settled, SimTradingEngine.executeSettle(settled, mapOf(stock.code to 40.0), original))
    }

    @Test fun `重复重跑只替换同日账本不会重复累计利润`() {
        val settled = SimTradingEngine.simulateDay(capital, "2026-11-05", rows(), config, fee)
        val base = SimAccountState(currentCapital = capital, cash = capital)
        val once = SimTradingJournal.post(base, settled)
        val twice = SimTradingJournal.post(once, settled)
        assertEquals(once, twice)
        assertEquals(1, twice.historyRecords.size)
        assertEquals(twice.currentCapital - twice.initialCapital, twice.totalProfit, 0.0)
    }

    @Test fun `回测报告与账户分别序列化且保留数据质量标记`() {
        val account = SimAccountState(currentCapital = capital, cash = capital)
        val before = SimAccountCodec.encode(account).toString()
        val result = SimTradingEngine.runBacktest(capital, mapOf("2026-11-05" to rows()), config, fee)
        val report = SimBacktestReport(123L, result, mapOf("2026-11-05" to "原始输入未验证"), emptyMap(), true)
        val restored = SimBacktestReport.fromJson(report.toJson())
        assertEquals(report, restored)
        assertEquals(before, SimAccountCodec.encode(account).toString())
    }

    @Test fun `旧账户载入不改变资产收益与历史订单`() {
        val original = SimAccountCodec.encode(SimAccountState(currentCapital = 1_095_588.508, totalProfit = 95_588.508, totalReturnPct = 9.5588508))
        val decoded = SimAccountCodec.decode(original)
        assertEquals(1_095_588.508, decoded.currentCapital, 0.0)
        assertEquals(9.5588508, decoded.totalReturnPct, 0.0)
        val oldDay = JSONObject().put("date", "2026-11-05").put("orders", JSONArray()).put("status", "SETTLED")
        assertEquals("LEGACY_PRICE_UNVERIFIED", SimDayRecord.fromJson(oldDay).priceQuality)
    }

    @Test fun `周末复制数据不参与复利回测`() {
        val result = SimTradingEngine.runBacktest(capital, mapOf("2026-10-31" to rows(), "2026-11-05" to rows()), config, fee)
        assertEquals(listOf("2026-11-05"), result.historyRecords.map { it.date })
    }

    @Test fun `收盘时间不能把旧价格自动升级为已确认`() {
        fun t(time: String) = ZonedDateTime.parse("2026-11-05T$time+08:00[Asia/Shanghai]").toInstant().toEpochMilli()
        assertEquals("LEGACY_PRICE_UNVERIFIED", SimTradingDataRepository.priceQuality("2026-11-05", 0L, t("16:00:00")))
        assertEquals("INTRADAY_ESTIMATE", SimTradingDataRepository.priceQuality("2026-11-05", t("14:00:00"), t("16:00:00")))
        assertEquals("CLOSE_CONFIRMED", SimTradingDataRepository.priceQuality("2026-11-05", t("15:00:00"), t("15:06:00")))
        assertEquals("LEGACY_PRICE_UNVERIFIED", SimTradingDataRepository.priceQuality("2026-11-04", t("15:00:00"), t("15:06:00")))
    }
}
