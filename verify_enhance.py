"""
动量翻转信号 + 回测对比算法验证(先跑通逻辑, 再移植 Kotlin)
"""
import numpy as np
import pandas as pd
from momentum import StrategyConfig, compute_stock_momentum
from data_fetcher import get_stock_daily

# ---------------------------------------------------------------------------
# 1) 动量翻转信号
# ---------------------------------------------------------------------------
def momentum_trend_signal(close: pd.Series, cfg: StrategyConfig) -> dict:
    """
    判断动量是在加速/衰减/转弱。
    思路: 用"短窗口动量(约2周=10交易日)" 对比 "中期动量(约3月=63交易日)"。
    - 短窗 > 中窗 且 均为正 → 加速(动量增强)
    - 短窗 < 中窗 → 衰减(动量减弱)
    - 中窗由正转负, 或短窗为负且中窗为正 → 转弱(明确卖出信号)
    """
    if len(close) < 70:
        return {"signal": "insufficient", "desc": "数据不足"}

    short_d = 10     # 约2周
    mid_d = 63       # 约3月

    r_short = np.log(close.iloc[-1] / close.iloc[-1 - short_d])
    r_mid = np.log(close.iloc[-1] / close.iloc[-1 - mid_d])

    # 之前一段的3月动量(用来判断"由正转负")
    r_mid_prev = np.log(close.iloc[-1 - short_d] / close.iloc[-1 - short_d - mid_d])

    if r_mid >= 0 and r_short >= 0 and r_short >= r_mid:
        return {"signal": "accel", "desc": "动量加速", "short": r_short, "mid": r_mid}
    elif r_mid >= 0 and r_short < r_mid:
        return {"signal": "decay", "desc": "动量衰减", "short": r_short, "mid": r_mid}
    elif r_mid_prev >= 0 and r_mid < 0:
        return {"signal": "reverse", "desc": "动量转弱(由正转负)", "short": r_short, "mid": r_mid}
    elif r_short < 0 and r_mid >= 0:
        return {"signal": "reverse", "desc": "动量转弱", "short": r_short, "mid": r_mid}
    else:
        return {"signal": "weak", "desc": "弱势区间", "short": r_short, "mid": r_mid}


# ---------------------------------------------------------------------------
# 2) 回测: 轮动策略 vs 等权持有 vs 单股
# ---------------------------------------------------------------------------
def backtest_rotation(codes: list, cfg: StrategyConfig, rebalance_days=10, lookback=250):
    """
    简化回测:
    - 数据: 每只股过去 lookback+ 交易日的前复权收盘
    - 轮动策略: 每 rebalance_days 个交易日, 按动量分选 top-k (这里选 top1 最强者) 持有
    - 等权: 全部等权持有
    - 输出: 累计收益, 年化, 最大回撤
    """
    closes = {}
    for code in codes:
        df = get_stock_daily(code, years=2)
        closes[code] = df["close"].reset_index(drop=True)

    # 对齐到最短长度
    min_len = min(len(c) for c in closes.values())
    closes = {k: v.iloc[-min_len:].reset_index(drop=True) for k, v in closes.items()}
    dates = np.arange(min_len)

    # 策略日收益矩阵
    rets = {}
    for code, c in closes.items():
        r = c.pct_change().fillna(0.0)
        rets[code] = r

    ret_df = pd.DataFrame(rets)

    # 等权组合日收益
    eq_daily = ret_df.mean(axis=1)

    # 轮动策略: 每个 rebalance 日选动量最强(近 lookback 窗口动量分最高)的一只持有
    rot_returns = []
    current_pick = None
    for i in range(min_len):
        if i == 0:
            rot_returns.append(0.0)
            continue
        rebalance_point = (i % rebalance_days == 0) or current_pick is None
        if rebalance_point:
            # 用截至 i-1 的数据算动量分, 选最高
            scores = {}
            for code in closes:
                window = closes[code].iloc[max(0, i - lookback):i]
                if len(window) < 30:
                    scores[code] = -np.inf
                    continue
                # 简化: 直接用过去 lookback 窗口对数收益作为动量
                scores[code] = np.log(window.iloc[-1] / window.iloc[0])
            current_pick = max(scores, key=scores.get)
        rot_returns.append(rets[current_pick].iloc[i])

    rot_series = pd.Series(rot_returns)
    eq_series = eq_daily

    def perf(daily: pd.Series):
        cum = (1 + daily).cumprod()
        total = cum.iloc[-1] - 1
        n_years = len(daily) / 252
        annual = (1 + total) ** (1 / n_years) - 1 if n_years > 0 else 0
        drawdown = (cum / cum.cummax() - 1).min()
        return total, annual, drawdown, cum

    rot_perf = perf(rot_series)
    eq_perf = perf(eq_series)

    # 各单股表现
    singles = {}
    for code in closes:
        singles[code] = perf(rets[code])[:3]

    return {
        "rotation": rot_perf[:3],
        "equal_weight": eq_perf[:3],
        "singles": singles,
        "rotation_cum": rot_perf[3],
        "equal_cum": eq_perf[3],
    }


if __name__ == "__main__":
    cfg = StrategyConfig()
    codes = ["603991", "300285", "600183", "300903", "300054"]

    print("=" * 70)
    print("1) 动量翻转信号验证")
    print("=" * 70)
    for code in codes:
        df = get_stock_daily(code, years=2)
        sig = momentum_trend_signal(df["close"], cfg)
        from data_fetcher import get_stock_name
        name = get_stock_name(code)
        print(f"  {code} {name:<8} → {sig['signal']:<8} {sig['desc']}"
              f"  (短窗{sig.get('short', float('nan')):+.2%} 中窗{sig.get('mid', float('nan')):+.2%})")

    print()
    print("=" * 70)
    print("2) 回测对比验证")
    print("=" * 70)
    bt = backtest_rotation(codes, cfg)
    print(f"  轮动策略: 累计{bt['rotation'][0]:+.2%}  年化{bt['rotation'][1]:+.2%}  最大回撤{bt['rotation'][2]:.2%}")
    print(f"  等权持有: 累计{bt['equal_weight'][0]:+.2%}  年化{bt['equal_weight'][1]:+.2%}  最大回撤{bt['equal_weight'][2]:.2%}")
    print("  各单股:")
    for code, (total, ann, dd) in bt['singles'].items():
        from data_fetcher import get_stock_name
        name = get_stock_name(code)
        print(f"    {code} {name:<8} 累计{total:+.2%} 年化{ann:+.2%} 回撤{dd:.2%}")
