# -*- coding: utf-8 -*-
"""
select_date.py —— 按指定日期, 基于“涨停池”快速选出符合 MYTT 的股票
=====================================================================
用法:
    python3 select_date.py 2026-09-23

原理:
  - 万手单 的最终条件要求 今收/昨收 > 1.095(即当日涨停附近 >+9.5%)。
    所以凡是可能命中的股票, 当天一定属于“涨停股”。
  - 直接用东财“涨停股池”接口拿那一天的涨停股做候选(单次、极快),
    再逐只抓日线算完整信号, 避免扫描全市场 5000+ 只。大幅提速。

注意:
  - 主要覆盖“封板/涨停”个股; 创业板/科创板(+20% 板)里当天仅涨 9.5%~19.99%
    而未涨停的个股不在本候选内(Q百板主要看主板涨停,属可接受的近似)。
输出:
  data/select_<date>.csv / _万手单.csv / _寻龙诀4号.csv
"""
import os
import sys
import time
import argparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
import pandas as pd

import akshare as ak
import data as D
from mytt import compute_mytt
from config import ADJUST, MIN_HIST_BARS, SLEEP_PER_REQUEST


def select(target_date: str, verbose: bool = True):
    td = pd.to_datetime(target_date)
    date8 = td.strftime("%Y%m%d")
    hist_start = (td - pd.Timedelta(days=1100)).strftime("%Y%m%d")

    # 1. 拿涨停池候选
    if verbose:
        print("[1/3] 获取 %s 涨停股池 ..." % target_date, flush=True)
    try:
        zt = ak.stock_zt_pool_em(date=date8)
    except Exception as e:
        print("获取涨停池失败:", repr(e)[:200])
        return {"combined": pd.DataFrame(), "wanshou": pd.DataFrame(), "xlj4": pd.DataFrame()}
    zt = zt.rename(columns={"代码": "code", "名称": "name", "涨跌幅": "chg_pct", "最新价": "price"})
    # 价>50 的不可能命中寻龙诀(要求 CLOSE<50), 但综合信号也需万手单, 先全部保留再算
    codes = zt["code"].astype(str).str.zfill(6).tolist()
    name_map = dict(zip(zt["code"].astype(str).str.zfill(6), zt["name"]))
    if verbose:
        print("    候选涨停股: %d 只" % len(codes), flush=True)

    combine, ws, xv = [], [], []
    for i, code in enumerate(codes):
        symbol = D.exchange_prefix(code) + code
        name = name_map.get(code, "")
        try:
            hist = D.load_daily(symbol, hist_start, end_date=date8, adjust=ADJUST)
        except Exception as e:
            if verbose:
                print("    [跳过] %s %s 历史获取失败: %s" % (code, name, repr(e)[:60]))
            time.sleep(SLEEP_PER_REQUEST)
            continue
        if len(hist) < MIN_HIST_BARS:
            time.sleep(SLEEP_PER_REQUEST)
            continue
        if hist["date"].iloc[-1].date() != td.date():
            time.sleep(SLEEP_PER_REQUEST)
            continue
        try:
            res = compute_mytt(hist)
        except Exception as e:
            if verbose:
                print("    [出错] %s %s: %s" % (code, name, repr(e)[:60]))
            time.sleep(SLEEP_PER_REQUEST)
            continue
        r = res.iloc[-1]
        last_date = hist["date"].iloc[-1]
        prev = hist.iloc[-2]
        chg = (hist.iloc[-1]["close"] / prev["close"] - 1) * 100
        d = {
            "code": code, "name": name,
            "date": str(last_date.date()),
            "close": round(float(r["close"]), 2),
            "chg_pct": round(float(chg), 2),
            "sig_wanshou": bool(r.get("sig_wanshou", False)),
            "sig_xlj4": bool(r.get("sig_xlj4", False)),
            "signal": bool(r.get("signal", False)),
            "WSD_25": bool(r.get("WSD_25", False)),
            "XLJ4_7": float(r.get("XLJ4_7", np.nan)),
        }
        if d["signal"]:
            combine.append(d)
        if d["sig_wanshou"]:
            ws.append(d)
        if d["sig_xlj4"]:
            xv.append(d)
        if verbose and (i + 1) % 10 == 0:
            print("    已处理 %d/%d | 综合%d 万手单%d 寻龙诀%d"
                  % (i + 1, len(codes), len(combine), len(ws), len(xv)), flush=True)
        time.sleep(SLEEP_PER_REQUEST)

    # 2. 保存
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
    os.makedirs(out_dir, exist_ok=True)
    base = os.path.join(out_dir, "select_%s" % target_date)
    for suffix, df_ in [("", pd.DataFrame(combine)),
                        ("_万手单", pd.DataFrame(ws)),
                        ("_寻龙诀4号", pd.DataFrame(xv))]:
        if len(df_) > 0:
            df_ = df_.sort_values("close", ascending=False).reset_index(drop=True)
            df_.to_csv(base + suffix + ".csv", index=False, encoding="utf-8-sig")
            if verbose:
                print("  已保存: %s.csv (%d 条)" % (base + suffix, len(df_)))
    if verbose:
        print("[3/3] 完成: 综合命中 %d, 万手单 %d, 寻龙诀4号 %d"
              % (len(combine), len(ws), len(xv)))
    return {"combined": pd.DataFrame(combine), "wanshou": pd.DataFrame(ws), "xlj4": pd.DataFrame(xv)}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("date", help="目标日期, 如 2026-09-23")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()
    select(args.date, verbose=not args.quiet)