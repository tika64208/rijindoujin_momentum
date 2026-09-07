"""
收益曲线对比算法验证(先跑通, 再移植 Kotlin)
- 动量策略: 每 rebalance 天按动量选 top 持有, 等权分配
- 均仓: 全部股票等权持有
- 个股: 各单股曲线
- 本金 100 万, 支持区间对比
"""
import numpy as np
import pandas as pd
from data_fetcher import get_stock_daily, get_stock_name

INITIAL_CAPITAL = 1_000_000.0  # 100万


def build_equity_curve(codes: list, years: float = 1.0, rebalance_days: int = 2, lookback: int = 5, top_n: int = 3):
    """构建各类净值曲线(以100万本金计), 返回 DataFrame 含日期索引和各策略净值。"""
    closes = {}
    for code in codes:
        df = get_stock_daily(code, years=2)
        closes[code] = df.set_index("date")["close"]

    # 对齐取最近 ~1年
    all_dates = closes[codes[0]].index
    for c in closes.values():
        all_dates = all_dates.intersection(c.index)
    n = int(252 * years)
    all_dates = all_dates[-n:]
    aligned = pd.DataFrame({c: closes[c].reindex(all_dates) for c in codes})
    aligned = aligned.ffill().dropna(how="any")

    rets = aligned.pct_change().fillna(0.0)
    dates = aligned.index

    # 均仓: 每日收益 = 各股平均
    eq_rets = rets.mean(axis=1)

    # 动量策略: 每 rebalance 天按 lookback 动量选 top_n, 等权持有
    rot_rets = np.zeros(len(dates))
    current_holdings = None
    for i in range(len(dates)):
        if i == 0:
            continue
        if i % rebalance_days == 0 or current_holdings is None:
            scores = {}
            for c in codes:
                start = max(0, i - lookback)
                window = aligned[c].iloc[start:i]
                if len(window) >= lookback:
                    scores[c] = np.log(window.iloc[-1] / window.iloc[0])
                else:
                    scores[c] = -np.inf
            ranked = sorted(scores, key=scores.get, reverse=True)
            current_holdings = ranked[:top_n]
        # 持有 top_n 等权
        rot_rets[i] = np.mean([rets[c].iloc[i] for c in current_holdings])

    # 转净值(100万本金)
    def to_nav(daily_rets):
        nav = INITIAL_CAPITAL * (1 + pd.Series(daily_rets, index=dates)).cumprod()
        return nav

    curves = {"动量策略": to_nav(rot_rets), "均仓持有": to_nav(eq_rets)}
    for c in codes:
        curves[get_stock_name(c)] = INITIAL_CAPITAL * (1 + rets[c]).cumprod()

    nav_df = pd.DataFrame(curves)
    return nav_df


def segment_compare(nav_df: pd.DataFrame, start_idx: int, end_idx: int):
    """区间对比: 给定起止位置, 返回各曲线区间收益。"""
    seg = nav_df.iloc[start_idx:end_idx + 1]
    result = {}
    for col in seg.columns:
        start_v = seg[col].iloc[0]
        end_v = seg[col].iloc[-1]
        result[col] = (end_v / start_v - 1) * 100  # 百分比
    return result


if __name__ == "__main__":
    codes = ["603991", "300285", "600183", "300903", "300054"]
    nav = build_equity_curve(codes, years=1.0)

    print("=" * 70)
    print("净值曲线(本金100万, 近1年) 首/末值")
    print("=" * 70)
    for col in nav.columns:
        print(f"  {col:<10} 起始{nav[col].iloc[0]:>12.0f} → 期末{nav[col].iloc[-1]:>12.0f}  (总收益{(nav[col].iloc[-1]/nav[col].iloc[0]-1)*100:+.1f}%)")

    print()
    print("区间对比示例(近3个月, 后63个交易日)")
    seg = segment_compare(nav, len(nav) - 63, len(nav) - 1)
    for k, v in seg.items():
        print(f"  {k:<10} 区间收益 {v:+.1f}%")

    print()
    print("曲线数据样本(前3行 + 末1行):")
    print(nav.head(3).round(0).to_string())
    print(nav.tail(1).round(0).to_string())
