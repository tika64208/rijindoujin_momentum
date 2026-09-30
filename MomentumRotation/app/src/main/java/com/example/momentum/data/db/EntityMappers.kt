package com.example.momentum.data.db

import com.example.momentum.data.DarkTradeDataSource
import com.example.momentum.engine.BidStrategy

/**
 * 实体类 (Entity) 与 领域模型 (Domain Model) 之间的通用映射扩展。
 */

fun BidDataEntity.toBidResult(): BidStrategy.BidResult =
    BidStrategy.BidResult(
        item = BidStrategy.BidItem(
            code = code,
            name = name,
            latestPrice = latestPrice,
            realChange = realChange,
            openPrice = openPrice,
            volume = volume,
            amount = amount,
            turnover = turnover,
            circMv = circMv,
            highPrice = 0.0,
            totalMv = circMv,
            bidChange = bidChange,
            warnFlag = warnFlag,
            industry = industry,
            province = "",
            conceptQuick = conceptQuick
        ),
        probability = probability,
        confidence = confidence,
        bidTurnover = bidTurnover,
        entityChange = entityChange,
        score = score,
        bidVolume = 0,
        warnText = warnText,
        excluded = excluded,
        excludeReason = excludeReason,
        reason = reason
    )

fun BidStrategy.BidItem.toEntity(
    date: String,
    result: BidStrategy.BidResult,
    savedAt: String
): BidDataEntity =
    BidDataEntity(
        code = code,
        date = date,
        name = name,
        latestPrice = latestPrice,
        realChange = realChange,
        openPrice = openPrice,
        volume = volume,
        amount = amount,
        turnover = turnover,
        circMv = circMv,
        bidChange = bidChange,
        warnFlag = warnFlag,
        industry = industry,
        conceptQuick = conceptQuick,
        probability = result.probability,
        confidence = result.confidence,
        score = result.score,
        bidTurnover = result.bidTurnover,
        entityChange = result.entityChange,
        warnText = result.warnText,
        reason = result.reason,
        savedAt = savedAt,
        excluded = result.excluded,
        excludeReason = result.excludeReason
    )

fun DarkTradeDataEntity.toRecord(): DarkTradeDataSource.DarkTradeRecord =
    DarkTradeDataSource.DarkTradeRecord(
        date = date,
        attr = "",
        code = code,
        name = name,
        industry = industry,
        concept = concept,
        buyAmount = buyAmount,
        sellAmount = sellAmount,
        totalAmount = totalAmount,
        volume = volume,
        ratio = ratio
    )

fun DarkTradeDataSource.DarkTradeRecord.toEntity(
    date: String,
    savedAt: String
): DarkTradeDataEntity =
    DarkTradeDataEntity(
        code = code,
        date = date,
        name = name,
        industry = industry,
        concept = concept,
        buyAmount = buyAmount,
        sellAmount = sellAmount,
        totalAmount = totalAmount,
        netAmount = netAmount,
        volume = volume,
        ratio = ratio,
        savedAt = savedAt
    )
