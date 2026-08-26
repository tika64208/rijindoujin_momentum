package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import com.example.momentum.model.Recommendation
import com.example.momentum.model.StockMomentum
import com.example.momentum.model.Tier
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 动量引擎: 风险调整动量评分 + 横截面轮动推荐。
 *
 * 公式:
 *   M = Σ(w_i · M_i) − λ·Volatility
 *   M_i = (R_i − Rf_i) / σ_i
 * 其中 R_i 为过去 windowsDays[i] 个交易日的对数收益。
 */
object MomentumEngine {

    /** 过去 days 个交易日的对数收益率, 数据不足返回 NaN。 */
    private fun logReturn(closes: List<Double>, days: Int): Double {
        if (closes.size < days + 1) return Double.NaN
        val p0 = closes[closes.size - days - 1]
        val p1 = closes.last()
        if (p0 <= 0.0 || p1 <= 0.0) return Double.NaN
        return ln(p1 / p0)
    }

    /** 计算单只股票的动量分。 */
    fun computeStockMomentum(
        code: String,
        name: String,
        bars: List<DailyBar>,
        cfg: StrategyConfig
    ): StockMomentum {
        val closes = bars.map { it.close }
        if (closes.size < cfg.minHistoryDays) {
            return StockMomentum(code, name, insufficient = true)
        }

        // 日对数收益率
        val rets = ArrayList<Double>(closes.size - 1)
        for (i in 1 until closes.size) {
            val r = ln(closes[i] / closes[i - 1])
            if (!r.isNaN() && !r.isInfinite()) rets.add(r)
        }
        if (rets.size < 2) return StockMomentum(code, name, insufficient = true)

        // 日波动率标准差
        val mean = rets.average()
        val variance = rets.sumOf { (it - mean) * (it - mean) } / (rets.size - 1)
        val dailyVol = sqrt(variance)
        val annualVol = dailyVol * sqrt(StrategyConfig.TRADING_DAYS_PER_YEAR.toDouble())

        // 每窗口风险调整动量
        val layerScores = HashMap<Int, Double>()
        var total = 0.0
        var totalW = 0.0
        for (idx in cfg.windowsDays.indices) {
            val days = cfg.windowsDays[idx]
            val w = cfg.windowWeights[idx]
            val r = logReturn(closes, days)
            if (r.isNaN()) continue
            // 窗口期无风险收益(按天数折算)
            val rf = cfg.riskFreeAnnual * (days / 252.0)
            val sigmaK = dailyVol * sqrt(days.toDouble()).coerceAtLeast(1e-8)
            val mk = (r - rf) / sigmaK
            layerScores[days] = mk
            total += w * mk
            totalW += w
        }

        // 默认展示用最长窗口的对数收益(若单窗口则即该窗口)
        val rShow = logReturn(closes, cfg.windowsDays.maxOrNull() ?: 5)
        if (totalW == 0.0) return StockMomentum(code, name, insufficient = true)

        val score = total / totalW - cfg.volPenaltyLambda * annualVol

        return StockMomentum(
            code = code,
            name = name,
            score = score,
            layerScores = layerScores,
            annualVol = annualVol,
            r12m = rShow,
            insufficient = false
        )
    }

    /**
     * 对自选池做横截面排名, 推荐只选 top N 只买入。
     * 其余: 排名最后的 top N 只标为卖出/减仓, 中间为持有。
     */
    fun rankAndRecommend(
        stocks: List<StockMomentum>,
        cfg: StrategyConfig
    ): Pair<List<Recommendation>, RankMeta> {
        val valid = stocks.filter { !it.insufficient && !it.score.isNaN() }
            .sortedByDescending { it.score }
        val skipped = stocks.filter { it.insufficient || it.score.isNaN() }

        val n = valid.size
        val topN = cfg.topCount.coerceAtMost(n).coerceAtLeast(1)
        val bottomN = topN  // 卖出档 = 排名最后的 top N 只

        val recs = valid.mapIndexed { i, s ->
            val rank = i + 1
            val tier = when {
                rank <= topN -> Tier.BUY
                rank > n - bottomN -> Tier.SELL
                else -> Tier.HOLD
            }
            Recommendation(
                code = s.code, name = s.name, score = s.score, tier = tier,
                annualVol = s.annualVol, r12m = s.r12m, rank = rank
            )
        }

        val meanScore = if (valid.isNotEmpty()) valid.map { it.score }.average() else Double.NaN
        val meta = RankMeta(
            total = n,
            skipped = skipped.size,
            meanScore = meanScore,
            topN = topN,
            bottomN = bottomN,
            skippedDetail = skipped.map { it.code to it.name }
        )
        return recs to meta
    }

    data class RankMeta(
        val total: Int,
        val skipped: Int,
        val meanScore: Double,
        val topN: Int,
        val bottomN: Int,
        val skippedDetail: List<Pair<String, String>>
    )
}
