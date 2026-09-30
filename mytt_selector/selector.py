# -*- coding: utf-8 -*-
"""
selector.py —— 选股调度
========================
流程:
  1. 取全市场代码列表
  2. 取当日快照 -> 预筛出少量候选股 (当日大涨、低价)
  3. 对每只候选股抓取数年日线历史(带缓存)
  4. 用 mytt.compute_mytt 计算信号
  5. 汇总命中信号的股票, 输出结果
"""
import os
import time
import sys

import numpy as np
import pandas as pd

from talc import REF
from config import (
    ADJUST, HIST_START, MIN_HIST_BARS,
    PREFILTER_MIN_CHG_PCT, PREFILTER_MAX_PRICE,
    SLEEP_PER_REQUEST, MAX_CANDIDATES,
)
import data as D
from mytt import compute_mytt


def run_select(verbose=True, save_csv=True, csv_path=None):
    if csv_path is None:
        from config import RESULT_CSV_PATH
        csv_path = RESULT_CSV_PATH

    # 1. 全市场代码列表
    if verbose:
        print("[1/5] 获取全市场股票列表 ...", flush=True)
    stock_list = D.get_stock_list()

    # 2. 当日快照 + 预筛
    if verbose:
        print("[2/5] 获取当日行情快照并预筛候选股 ...", flush=True)
    snapshot = D.fetch_snapshot()
    cand = D.prefetch_candidates(
        snapshot,
        min_chg_pct=PREFILTER_MIN_CHG_PCT,
        max_price=PREFILTER_MAX_PRICE,
    )
    cand = cand.merge(stock_list, on="code", how="left", suffixes=("", "_list"))
    if "name" in cand and ("name_list" in cand.columns):
        cand["name"] = cand["name"].fillna(cand["name_list"])
        cand = cand.drop(columns=["name_list"])
    cand = cand.head(MAX_CANDIDATES)
    if verbose:
        print("    预筛候选股数量: %d" % len(cand), flush=True)
    if len(cand) == 0:
        print("    今日上涨超 %.1f%% 的低价股为 0 只, 无候选。可能已收盘太久或当日无大涨股。" % PREFILTER_MIN_CHG_PCT)
        return {"combined": pd.DataFrame(), "wanshou": pd.DataFrame(), "xlj4": pd.DataFrame()}

    # 3. 逐只抓取日线 + 计算信号
    combine_hits = []   # 万手单 AND 寻龙诀4号 都命中
    ws_hits = []        # 至少命中万手单
    xv_hits = []        # 至少命中寻龙诀4号
    for idx, row in cand.iterrows():
        code = row["code"]
        name = row["name"]
        symbol = D.exchange_prefix(code) + code
        try:
            hist = D.load_daily(symbol, HIST_START, adjust=ADJUST)
        except Exception as e:
            if verbose:
                print("    [跳过] %s %s 历史获取失败: %s" % (code, name, repr(e)[:80]))
            continue
        if len(hist) < MIN_HIST_BARS:
            if verbose:
                print("    [跳过] %s %s 历史不足 %d 根: 只有 %d 根" % (code, name, MIN_HIST_BARS, len(hist)))
            continue
        if verbose and idx % 10 == 0 and idx > 0:
            print("    已处理 %d/%d 只 ..." % (idx, len(cand)), flush=True)
        try:
            result = compute_mytt(hist)
        except Exception as e:
            if verbose:
                print("    [出错] %s %s 计算失败: %s" % (code, name, repr(e)[:80]))
            continue
        last = result.iloc[-1]
        d = {
            "code": code,
            "name": name,
            "date": str(hist["date"].iloc[-1].date()),
            "close": round(float(last["close"]), 2),
            "sig_wanshou": bool(last.get("sig_wanshou", False)),
            "sig_xlj4": bool(last.get("sig_xlj4", False)),
            "signal": bool(last.get("signal", False)),
            "WSD_25": bool(last.get("WSD_25", False)),
            "XLJ4_7": float(last.get("XLJ4_7", np.nan)),
        }
        if d["signal"]:
            combine_hits.append(d)
        if d["sig_wanshou"]:
            ws_hits.append(d)
        if d["sig_xlj4"]:
            xv_hits.append(d)
        time.sleep(SLEEP_PER_REQUEST)

    res = pd.DataFrame(combine_hits)
    res_ws = pd.DataFrame(ws_hits)
    res_xv = pd.DataFrame(xv_hits)
    if verbose:
        print("[3/5] 扫描完成: 候选 %d 只; 万手单命中 %d, 寻龙诀4号命中 %d, "
              "双信号命中 %d" % (len(cand), len(res_ws), len(res_xv), len(res)), flush=True)

    # 4. 汇总输出
    for df_ in (res, res_ws, res_xv):
        if len(df_) > 0:
            df_["触发信号"] = df_.apply(
                lambda r: "万手单 AND 寻龙诀4号" if r["signal"] else (
                    "仅万手单" if r["sig_wanshou"] else "仅寻龙诀4号"
                ), axis=1)
            df_ = df_.sort_values("close", ascending=False).reset_index(drop=True)

    if save_csv and csv_path:
        # 输出三份: 综合命中 / 万手单 / 寻龙诀
        try:
            base = os.path.splitext(csv_path)[0]
            df_list = [("", res), ("_万手单", res_ws), ("_寻龙诀4号", res_xv)]
            for suffix, df_ in df_list:
                if len(df_) > 0:
                    df_.to_csv(base + suffix + ".csv", index=False, encoding="utf-8-sig")
            if verbose:
                print("    结果已保存: %s (及 _万手单 / _寻龙诀4号 分表)" % os.path.abspath(csv_path))
        except Exception as e:
            if verbose:
                print("    保存 CSV 失败: %s" % repr(e))

    return {"combined": res, "wanshou": res_ws, "xlj4": res_xv}