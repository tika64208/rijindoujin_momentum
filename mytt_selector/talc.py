# -*- coding: utf-8 -*-
"""
talc.py —— 通达信函数库的 Python 翻译
======================================
把通达信公式系统里常用的函数，翻译成等价的 numpy/pandas 计算方法。

所有函数都以 pandas.Series（按时间从小到大排列的日线序列）作为输入，
返回和输入等长的 Series，索引保持一致，这样多条公式可以方便地组合。

已实现：
    MA     均线                  REF    向前引用
    HHV    最高值                LLV    最低值
    BARSLAST  距上次为真的周期数  FILTER  信号过滤
    COUNT    周期内次数          CROSS   上穿
    DMA      动态移动平均
    CONVERT_BOOL  布尔转 1/0

说明：
    - 本文件只做“翻译层”，不含任何行情获取逻辑。
    - FILTER/BARSLAST 等带“记忆”的函数，用纯 pandas/循环实现，
      保证和通达信语义一致。
"""
import numpy as np
import pandas as pd


def MA(x, n):
    """N 日均线，包含当前周期。X 为 Series。"""
    return x.rolling(window=n, min_periods=1).mean()


def REF(x, n):
    """n 个周期前的值。n=0 即当前。"""
    return x.shift(n)


def HHV(x, n):
    """N 周期最高值，包含当前周期。n 可为整数或 Series(动态窗口)。"""
    if isinstance(n, (int, np.integer)) or not hasattr(n, "shape"):
        return x.rolling(window=int(n), min_periods=1).max()
    return _dyn_roll(x, n, np.max)


def LLV(x, n):
    """N 周期最低值，包含当前周期。n 可为整数或 Series(动态窗口)。"""
    if isinstance(n, (int, np.integer)) or not hasattr(n, "shape"):
        return x.rolling(window=int(n), min_periods=1).min()
    return _dyn_roll(x, n, np.min)


def _dyn_roll(x, n_series, func):
    """
    动态窗口滚动聚合：对每个位置 i，取第 [i - n[i] + 1, i] 一共 n[i] 个数做 func。
    n_series 为每个位置对应的窗口长度（整数）。用于 HHV(HIGH, XLJ4_7) 这类场景。
    """
    arr = x.to_numpy()
    n_raw = n_series.to_numpy()
    out = np.empty(len(arr))
    out[:] = np.nan
    for i in range(len(arr)):
        w_raw = n_raw[i]
        if not np.isfinite(w_raw) or w_raw < 1:
            # 窗口无效(如 BARSLAST 尚未出现过信号)时退化为只看当前值
            out[i] = arr[i] if np.isfinite(arr[i]) else np.nan
            continue
        w = int(round(w_raw))
        lo = max(0, i - w + 1)
        window = arr[lo : i + 1]
        finite = window[np.isfinite(window)]
        if finite.size == 0:
            out[i] = np.nan
        else:
            out[i] = func(finite)
    return pd.Series(out, index=x.index)


def BARSLAST(cond):
    """
    上一次 cond 为真(非0) 距现在的周期数。
    - 若当前 cond 为真，返回 0。
    - 若为假，返回距上一个真值的间隔(>0)。
    - 若历史上从未为真(或全为 NaN 前)，返回 NaN。
    """
    c = cond.fillna(False).astype(bool).to_numpy()
    out = np.full(len(c), np.nan)
    last_true = -1
    arr = cond.to_numpy()
    for i in range(len(c)):
        # 若当前为真，则 BARSLAST=0；其余情况为 i - last_true
        cur_true = arr[i] not in (0, False) and not (
            isinstance(arr[i], float) and np.isnan(arr[i])
        )
        if cur_true:
            out[i] = 0
            last_true = i
        else:
            if last_true >= 0:
                out[i] = i - last_true
            else:
                out[i] = np.nan
    return pd.Series(out, index=cond.index)


def FILTER(x, n):
    """
    信号过滤：x 为真(非0)时立即输出有效信号，但输出后接下来的 n 个周期
    内即使 x 再为真也屏蔽（视为0），避免连续重复触发。
    """
    arr = x.to_numpy()
    n = int(n)
    out = np.zeros(len(arr), dtype=bool)
    suppressed_until = -1
    for i in range(len(arr)):
        cur_true = arr[i] not in (0, False) and not (
            isinstance(arr[i], float) and np.isnan(arr[i])
        )
        if cur_true and i > suppressed_until:
            out[i] = True
            suppressed_until = i + n  # 接下来的 n 个周期屏蔽
    return pd.Series(out, index=x.index)


def COUNT(cond, n):
    """最近 n 个周期（含当前）内 cond 为真(非0) 的次数。"""
    c = cond.astype(float)
    true_mask = c.notna() & (c != 0)
    return true_mask.rolling(window=int(n), min_periods=1).sum()


def CROSS(a, b):
    """a 上穿 b：上一周期 a<=b 且 本周期 a>b。返回布尔。"""
    a = pd.Series(a).astype(float)
    b = pd.Series(b).astype(float)
    prev = (a.shift(1) <= b.shift(1)) & a.shift(1).notna() & b.shift(1).notna()
    cur = (a > b) & a.notna() & b.notna()
    return prev & cur


def DMA(x, a):
    """
    动态移动平均：
        Y[i] = a[i] * x[i] + (1 - a[i]) * Y[i-1]
        初始 Y[0] = x[0]。
    a 会先被限定到 [0,1]（与通达信一致）。
    """
    x = x.to_numpy(dtype=float)
    a = np.clip(a.to_numpy(dtype=float), 0.0, 1.0)
    out = np.empty(len(x))
    out[:] = np.nan
    if len(x) == 0:
        return pd.Series(out)
    out[0] = x[0]
    for i in range(1, len(x)):
        ai = a[i]
        out[i] = ai * x[i] + (1 - ai) * out[i - 1]
    return pd.Series(out)


def CONVERT_BOOL(s):
    """布尔/数值 Series 转成 0/1 数值。"""
    s = pd.Series(s)
    mask = s.notna() & (s != 0)
    return mask.astype(float)