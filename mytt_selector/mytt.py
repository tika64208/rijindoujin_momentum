# -*- coding: utf-8 -*-
"""
mytt.py —— MYTT 选股公式的 Python 实现
=======================================
把用户提供的通达信公式（万手单 + 寻龙诀4号）逐条翻译成 pandas 计算。

输入：一个 DataFrame（日线行情），列名固定为：
    open, high, low, close, volume, amount, ...
    按日期升序排列（最早在前，最新在后），行索引为日期。

返回：DataFrame，原来的每一列 + 中间变量列 + 最终信号列：
    sig_wanshou    万手单
    sig_xlj4       寻龙诀4号
    signal         OUTPUT = 万手单 AND 寻龙诀4号
"""
import numpy as np
import pandas as pd

from talc import (
    MA, REF, HHV, LLV, BARSLAST, FILTER, COUNT, CROSS, DMA, CONVERT_BOOL,
)


def compute_mytt(df: pd.DataFrame) -> pd.DataFrame:
    """
    输入日线 df（必须含 open/high/low/close/volume/amount，按日期升序），
    输出含 signal 信号的 DataFrame。
    """
    close = df["close"].astype(float)
    open_ = df["open"].astype(float)
    high = df["high"].astype(float)
    low = df["low"].astype(float)
    vol = df["volume"].astype(float)
    amount = df["amount"].astype(float) if "amount" in df.columns else pd.Series(np.nan, index=df.index)

    out = df.copy()

    # ============================================================
    # 一、万手单 (WSD)
    # ============================================================
    WSD_1 = MA(close, 10)                      # MA(CLOSE,10)
    WSD_2 = MA(close, 54)                      # MA(CLOSE,54)
    # WSD_3: (REF(CLOSE,3)-CLOSE)/REF(CLOSE,3)*100 > 5  → 3日前较今收跌超5%
    WSD_3 = (REF(close, 3) - close) / REF(close, 3) * 100 > 5
    WSD_4 = FILTER(WSD_3, 10)                  # 过滤
    WSD_5 = BARSLAST(WSD_4)                    # 距最近一次大跌的周期数
    # WSD_6/7/8 的 REF 偏移量是动态序列(WSD_5)，用循环映射实现
    WSD_6 = _ref_dyn(high, WSD_5 + 2)          # REF(HIGH,WSD_5+2)
    WSD_7 = _ref_dyn(high, WSD_5 + 1)          # REF(HIGH,WSD_5+1)
    WSD_8 = _ref_dyn(high, WSD_5)              # REF(HIGH,WSD_5)
    WSD_9 = np.maximum(WSD_6, WSD_7)           # MAX
    WSD_10 = np.maximum(WSD_9, WSD_8)          # MAX
    WSD_11 = (close - REF(close, 1)) / REF(close, 1) * 100 > 5   # 今日大涨>5%
    WSD_12 = WSD_5 < 150                       # 距上次大跌在150天内
    WSD_13 = (open_ - WSD_10) / WSD_10 * 100 < 30
    WSD_14 = (close - LLV(low, WSD_5)) / LLV(low, WSD_5) * 100 < 50
    WSD_15 = (close - REF(open_, 5)) / REF(open_, 5) * 100 < 30
    WSD_16 = vol / MA(vol, 5) < 3.5
    WSD_17 = (close - REF(close, 89)) / REF(close, 89) * 100 < 80
    WSD_18 = (WSD_11 & WSD_12 & WSD_13 & WSD_14 & WSD_15 & WSD_16 & WSD_17)
    WSD_19 = FILTER(WSD_18, 15)
    WSD_20 = (close - WSD_2) / WSD_2 < 0.1
    WSD_21 = (close - WSD_1) / WSD_1 < 0.3
    WSD_22 = CONVERT_BOOL(WSD_20 & WSD_21) * 0.2   # (WSD20=1 AND WSD21=1)*0.2

    # WSD_23 = (WSD_22=0 AND REF(WSD_22,1)=0.2 AND REF(COUNT(WSD_22=0.2,10)=10,1)=1) * (-0.1)
    _cnt22 = COUNT(WSD_22 == 0.2, 10)
    WSD_23 = (
        (WSD_22 == 0)
        & (REF(WSD_22, 1) == 0.2)
        & REF(_cnt22 == 10, 1)
    ) * (-0.1)

    WSD_24 = WSD_23 == (-0.1)
    WSD_25 = WSD_19 | WSD_24

    # ============================================================
    # 二、万手单 最终
    # ============================================================
    # WSD_26 := DMA(AMOUNT/VOL/100, VOL/CAPITAL)  —— 死代码，本公式未引用，跳过
    # WSD_27: DYNAINFO(7)=今现价，用户选择用“当日收盘价”替代
    #     DYNAINFO(7)/REF(CLOSE,1)>1.05 AND DYNAINFO(7)/OPEN>1.05
    #     => CLOSE/REF(CLOSE,1)>1.05 AND CLOSE/OPEN>1.05
    WSD_27 = (close / REF(close, 1) > 1.05) & (close / open_ > 1.05)
    # WSD_28: (VOL/REF(VOL,1)>1.2 AND COUNT(CLOSE>OPEN,1))
    #        OR (LOW>REF(HIGH,1) AND COUNT(OPEN>CLOSE,1) AND VOL/REF(VOL,1)>1.2)
    _vol_ratio = vol / REF(vol, 1)
    WSD_28 = (
        (_vol_ratio > 1.2) & (CONVERT_BOOL(close > open_) > 0)
    ) | (
        (low > REF(high, 1))
        & (CONVERT_BOOL(open_ > close) > 0)
        & (_vol_ratio > 1.2)
    )
    sig_wanshou = (
        WSD_25
        & (close / REF(close, 1) > 1.095)
        & WSD_28
        & WSD_27
    )

    # ============================================================
    # 三、寻龙诀4号 (XLJ4)
    # ============================================================
    XLJ4_1 = (close - REF(close, 1)) / REF(close, 1) * 100          # 当日涨跌幅%
    XLJ4_2 = REF(close, 10) == HHV(close, 21)                        # 10日前收盘==21日最高
    XLJ4_3 = FILTER(XLJ4_2, 10)
    XLJ4_4 = FILTER(XLJ4_3, 11)
    XLJ4_5 = FILTER(XLJ4_4, 10)
    XLJ4_7 = BARSLAST(XLJ4_5)                                        # 距上次信号周期数
    XLJ4_6 = _ref_dyn(close, XLJ4_7)                                 # 上次信号当日收盘价(四号线)
    XLJ4_8 = (HHV(high, XLJ4_7) - LLV(low, XLJ4_7)) / LLV(low, XLJ4_7) * 100  # 区间振幅%
    sig_xlj4 = (
        (REF(XLJ4_7, 1) > 10)
        & (REF(XLJ4_8, 1) < 100)
        & CROSS(close, XLJ4_6)
        & ((vol >= REF(vol, 1) * 2) | (vol >= REF(vol, 2) * 2))
        & (XLJ4_1 > 2)
        & (close < 50)
    )

    # ============================================================
    # 四、最终信号 OUTPUT: 万手单 AND 寻龙诀4号
    # ============================================================
    signal = sig_wanshou & sig_xlj4

    # 记录中间变量（便于调试/追溯）
    out["WSD_1"] = WSD_1
    out["WSD_2"] = WSD_2
    out["WSD_5"] = WSD_5
    out["WSD_10"] = WSD_10
    out["WSD_19"] = WSD_19
    out["WSD_24"] = WSD_24
    out["WSD_25"] = WSD_25
    out["XLJ4_6"] = XLJ4_6
    out["XLJ4_7"] = XLJ4_7
    out["XLJ4_8"] = XLJ4_8
    out["sig_wanshou"] = sig_wanshou
    out["sig_xlj4"] = sig_xlj4
    out["signal"] = signal
    return out


def _ref_dyn(s: pd.Series, n: pd.Series):
    """
    动态 REF：对每个位置 i，取 s[i - n[i]] 处的值。
    n[i] 为各位置要回溯的周期数（可为浮点，向下取整）。
    当 i - n[i] < 0 或为 NaN 时返回 NaN。
    """
    arr = s.to_numpy()
    n = n.to_numpy()
    out = np.full(len(arr), np.nan)
    for i in range(len(arr)):
        k = n[i]
        if np.isnan(k):
            continue
        k = int(round(k))
        j = i - k
        if j >= 0 and j < len(arr):
            out[i] = arr[j]
    return pd.Series(out, index=s.index)