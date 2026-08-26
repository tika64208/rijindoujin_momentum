"""
动量轮动策略核心模块
====================
实现式(1)(2) 的风险调整动量评分，以及自选池内的横截面轮动推荐。

动量得分 M = w1·M_1m + w2·M_3m + w3·M_6m + w4·M_12m  −  λ·Volatility
其中每层 M_k = (R_k − Rf_k) / σ_k   (风险调整动量)

仅依赖 pandas/numpy，不依赖具体数据源。
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Optional

import numpy as np
import pandas as pd


# ---------------------------------------------------------------------------
# 策略参数
# ---------------------------------------------------------------------------
@dataclass
class StrategyConfig:
    windows_months: tuple = (1, 3, 6, 12)          # 观察窗口(月)
    window_weights: tuple = (0.40, 0.30, 0.20, 0.10)  # 各窗口权重，近期更高
    vol_penalty_lambda: float = 0.30               # 波动率惩罚系数 λ
    risk_free_annual: float = 0.015                 # 无风险年化利率(近似, 可配)
    top_ratio: float = 0.20                         # 买入档位比例(前20%)
    bottom_ratio: float = 0.20                      # 卖出档位比例(后20%)
    min_history_days: int = 20                      # 最少需要的历史交易日(不足则降级)
    liquidity_min_amount: float = 5e7               # 流动性过滤: 日均成交额下限(5000万)
    benchmark_days: int = 250                       # 基准动量计算用的回看交易日(约1年)

    def __post_init__(self):
        assert abs(sum(self.window_weights) - 1.0) < 1e-6, "窗口权重之和应为 1"
        assert len(self.windows_months) == len(self.window_weights)


# ---------------------------------------------------------------------------
# 辅助: 交易日换算(月 -> 交易日)
# ---------------------------------------------------------------------------
TRADING_DAYS_PER_MONTH = 21


def months_to_days(months: int) -> int:
    return months * TRADING_DAYS_PER_MONTH


# ---------------------------------------------------------------------------
# 单只股票: 风险调整动量
# ---------------------------------------------------------------------------
@dataclass
class StockMomentum:
    code: str                      # 股票代码(6位)
    name: str                      # 名称
    score: float = float("nan")    # 总动量分 M
    layer_scores: dict = field(default_factory=dict)  # {窗口月数: M_k}
    annual_vol: float = float("nan")   # 年化波动率
    r_12m: float = float("nan")        # 12个月对数收益(展示用)
    insufficient: bool = False         # 历史数据不足


def log_return(close: pd.Series, days: int) -> float:
    """过去 days 个交易日的对数收益率, 数据不足返回 nan。"""
    if len(close) < days + 1:
        return float("nan")
    p0 = close.iloc[-(days + 1)]
    p1 = close.iloc[-1]
    if p0 <= 0 or p1 <= 0:
        return float("nan")
    return float(np.log(p1 / p0))


def compute_stock_momentum(
    code: str,
    name: str,
    close: pd.Series,          # 前复权收盘价, 按日期升序
    cfg: StrategyConfig,
) -> StockMomentum:
    sm = StockMomentum(code=code, name=name)

    if len(close) < cfg.min_history_days:
        sm.insufficient = True
        return sm

    # 日对数收益率, 用于波动率
    rets = np.log(close / close.shift(1)).dropna()
    if len(rets) < 2:
        sm.insufficient = True
        return sm

    daily_vol = float(rets.std(ddof=1))
    sm.annual_vol = daily_vol * math.sqrt(252)

    # 每窗口的风险调整动量
    total = 0.0
    total_w = 0.0
    for months, w in zip(cfg.windows_months, cfg.window_weights):
        r = log_return(close, months_to_days(months))
        # 该窗口的无风险收益(按相同跨度折算)
        rf = cfg.risk_free_annual * (months / 12.0)
        if math.isnan(r):
            continue  # 数据不足的窗口跳过, 权重重新归一
        sigma_k = daily_vol * math.sqrt(months_to_days(months))  # 窗口期波动
        sigma_k = max(sigma_k, 1e-8)
        mk = (r - rf) / sigma_k
        sm.layer_scores[months] = mk
        total += w * mk
        total_w += w

    sm.r_12m = log_return(close, months_to_days(12))

    if total_w == 0:
        sm.insufficient = True
        return sm

    # 归一化权重 + 波动率惩罚
    score = total / total_w
    score -= cfg.vol_penalty_lambda * sm.annual_vol
    sm.score = score
    return sm


# ---------------------------------------------------------------------------
# 横截面: 自选池轮动推荐
# ---------------------------------------------------------------------------
@dataclass
class Recommendation:
    code: str
    name: str
    score: float
    tier: str                 # buy / hold / sell
    annual_vol: float
    r_12m: float
    rank: int
    note: str = ""


def rank_and_recommend(
    stocks: list[StockMomentum], cfg: StrategyConfig
) -> tuple[list[Recommendation], dict]:
    """对自选池做横截面排名, 输出三档推荐。"""
    valid = [s for s in stocks if not s.insufficient and not math.isnan(s.score)]
    skipped = [s for s in stocks if s.insufficient or math.isnan(s.score)]

    valid.sort(key=lambda s: s.score, reverse=True)

    n = len(valid)
    top_n = max(1, int(round(n * cfg.top_ratio)))
    bottom_n = max(1, int(round(n * cfg.bottom_ratio)))

    recs: list[Recommendation] = []
    for rank, s in enumerate(valid, start=1):
        if rank <= top_n:
            tier = "buy"
        elif rank > n - bottom_n:
            tier = "sell"
        else:
            tier = "hold"
        recs.append(
            Recommendation(
                code=s.code, name=s.name, score=s.score, tier=tier,
                annual_vol=s.annual_vol, r_12m=s.r_12m, rank=rank,
            )
        )

    # 基准(池内均值)超额动量参考
    mean_score = float(np.mean([s.score for s in valid])) if valid else float("nan")
    meta = {
        "total": n,
        "skipped": len(skipped),
        "mean_score": mean_score,
        "top_n": top_n,
        "bottom_n": bottom_n,
        "skipped_detail": [(s.code, s.name) for s in skipped],
    }
    return recs, meta
