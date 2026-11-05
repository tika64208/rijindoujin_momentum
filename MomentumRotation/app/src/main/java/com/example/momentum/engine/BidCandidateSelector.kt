package com.example.momentum.engine

import com.example.momentum.data.db.BidDataEntity

/** 今日模拟与历史回测共用。这里只读取已保存评分，禁止从盘中成交量重新计算。 */
object BidCandidateSelector {
    fun select(rows: List<BidDataEntity>, config: BidStrategy.BidConfig) =
        rows.filter { matches(it, config) }.sortedWith(compareByDescending<BidDataEntity> { it.probability }.thenBy { it.code })

    fun matches(e: BidDataEntity, cfg: BidStrategy.BidConfig): Boolean {
        if (!e.openPrice.isFinite() || e.openPrice <= 0 || !e.bidChange.isFinite() || !e.circMv.isFinite()) return false
        if (cfg.filterStSuspend && (e.name.contains("ST", true) || e.name.contains("停", true))) return false
        if (cfg.filterLimitUp && e.warnFlag >= 5) return false
        if (!cfg.marketHs && !cfg.marketCyb && !cfg.marketKcb) return false
        val isHs = (e.code.startsWith("6") && !e.code.startsWith("688")) || e.code.startsWith("00")
        if (isHs && !cfg.marketHs || e.code.startsWith("3") && !cfg.marketCyb || e.code.startsWith("688") && !cfg.marketKcb) return false
        fun outside(value: Double, min: Double, max: Double) = min > 0 && value < min || max > 0 && value > max
        if (outside(e.bidChange, cfg.bidMin, cfg.bidMax) || outside(e.probability.toDouble(), cfg.probMin, cfg.probMax) ||
            outside(e.confidence.toDouble(), cfg.confMin, cfg.confMax) || outside(e.score.toDouble(), cfg.scoreMin, cfg.scoreMax) ||
            outside(e.circMv / 1e8, cfg.mvMin, cfg.mvMax) || outside(e.openPrice, cfg.priceMin, cfg.priceMax)) return false
        return !cfg.filterLargeVolume || e.volume >= cfg.filterLargeVolumeThreshold
    }
}
