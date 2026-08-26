"""
动量轮动选股助手 · 命令行原型
============================
用法:
    python main.py                        # 用内置示例自选股
    python main.py 600519 000858 601318   # 指定自选股代码

流程: 下载前复权日线 -> 计算风险调整动量 -> 横截面排名 -> 三档推荐输出
"""
from __future__ import annotations

import sys

import pandas as pd

from momentum import (
    StrategyConfig,
    StockMomentum,
    compute_stock_momentum,
    rank_and_recommend,
)
from data_fetcher import get_stock_daily, get_stock_name

# 示例自选股(覆盖不同板块, 方便验证)
SAMPLE_WATCHLIST = [
    "600519",  # 贵州茅台
    "000858",  # 五粮液
    "601318",  # 中国平安
    "600036",  # 招商银行
    "000333",  # 美的集团
    "600900",  # 长江电力
    "002594",  # 比亚迪
    "300750",  # 宁德时代
    "601899",  # 紫金矿业
    "600276",  # 恒瑞医药
]


def fetch_and_compute(codes: list[str], cfg: StrategyConfig) -> tuple[list[StockMomentum], dict]:
    results: list[StockMomentum] = []
    details = {}
    for code in codes:
        try:
            df = get_stock_daily(code, years=2)
            name = get_stock_name(code)
            sm = compute_stock_momentum(code, name, df["close"], cfg)
            results.append(sm)
            details[code] = (name, len(df))
        except Exception as e:  # noqa: BLE001
            print(f"  [跳过] {code}: {e}")
            details[code] = ("ERROR", str(e))
    return results, details


def render(recs, meta, cfg):
    tier_label = {"buy": "🟢 买入/加仓", "hold": "🟡 持有/观察", "sell": "🔴 卖出/减仓"}
    tier_order = {"buy": 0, "hold": 1, "sell": 2}

    print("\n" + "=" * 70)
    print("动量轮动推荐(自选池内横截面排名, 前复权, 风险调整动量)")
    print("=" * 70)
    print(
        f"池内有效 {meta['total']} 只 | 跳过 {meta['skipped']} 只 | "
        f"池均值分 {meta['mean_score']:.3f}"
    )
    print(f"参数: 窗口{cfg.windows_months} 权重{cfg.window_weights} λ={cfg.vol_penalty_lambda}")
    print("-" * 70)

    for tier in ("buy", "hold", "sell"):
        group = [r for r in recs if r.tier == tier]
        if not group:
            continue
        print(f"\n【{tier_label[tier]}】({len(group)} 只)")
        for r in sorted(group, key=lambda x: x.score, reverse=True):
            print(
                f"  {r.rank:>2}. {r.code} {r.name:<8} "
                f"动量分 {r.score:>7.2f}  年化波动 {r.annual_vol:.2%}  12月收益 {r.r_12m:>8.2%}"
            )

    print("\n" + "=" * 70)
    if meta["skipped_detail"]:
        skipped = ", ".join(f"{c}({n})" for c, n in meta["skipped_detail"])
        print(f"⚠ 数据不足/异常跳过: {skipped}")
    print("注: 动量轮动在震荡市易反复, 建议结合止损与仓位管理。")


def main(codes: list[str]):
    cfg = StrategyConfig()
    print(f"开始处理 {len(codes)} 只自选股, 下载前复权日线(约2年)...")
    stocks, details = fetch_and_compute(codes, cfg)

    if not stocks:
        print("无有效数据, 退出。")
        return

    recs, meta = rank_and_recommend(stocks, cfg)
    render(recs, meta, cfg)


if __name__ == "__main__":
    codes = sys.argv[1:] if len(sys.argv) > 1 else SAMPLE_WATCHLIST
    main(codes)
