# -*- coding: utf-8 -*-
"""
scan_date.py —— 按指定日期扫描全市场, 复现某一天的选股结果
=========================================================
用途: 复现“9月23号”等历史交易日的选股记录。

与 run.py 的区别:
  - run.py 用“当日实时快照”预筛候选, 适合每天收盘后跑。
  - scan_date.py 不依赖快照, 直接遍历全市场, 用目标日期当天的
    涨跌幅做预筛, 再算完整信号。适合回溯任意历史日期。

用法:
    python3 scan_date.py 2026-09-23
    python3 scan_date.py 2026-09-23 --limit 2000        # 只扫前2000只(测试)
    python3 scan_date.py 2026-09-23 --start 2000        # 从第2000只开始(续跑)

输出:
    data/scan_<date>.csv        综合命中(万手单 AND 寻龙诀4号)
    data/scan_<date>_万手单.csv
    data/scan_<date>_寻龙诀4号.csv
"""
import os
import sys
import time
import argparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
import pandas as pd

import data as D
from mytt import compute_mytt
from config import ADJUST, MIN_HIST_BARS, SLEEP_PER_REQUEST


def scan(target_date: str, start: int = 0, limit: int = None, verbose: bool = True):
    td = pd.to_datetime(target_date)
    end = td.strftime("%Y%m%d")
    # 拉历史起点: 目标日期往前推约 3 年, 保证足够 lookback
    start_dt = td - pd.Timedelta(days=1100)
    hist_start = start_dt.strftime("%Y%m%d")

    stock_list = D.get_stock_list()
    codes = stock_list["code"].tolist()
    names = dict(zip(stock_list["code"], stock_list["name"]))
    total = len(codes)
    if limit is not None:
        codes = codes[start:start + limit]
    else:
        codes = codes[start:]
    if verbose:
        print("目标日期 %s | 待扫描 %d 只 (从第 %d 只开始)"
              % (target_date, len(codes), start), flush=True)

    combine = []
    ws = []
    xv = []
    scanned = 0
    skipped = 0
    t0 = time.time()
    for i, code in enumerate(codes):
        symbol = D.exchange_prefix(code) + code
        name = names.get(code, "")
        try:
            hist = D.load_daily(symbol, hist_start, end_date=end, adjust=ADJUST)
        except Exception as e:
            skipped += 1
            if verbose and i % 200 == 0:
                print("  [err] %s %s: %s" % (code, name, repr(e)[:60]))
            time.sleep(SLEEP_PER_REQUEST)
            continue
        if len(hist) < MIN_HIST_BARS:
            skipped += 1
            time.sleep(SLEEP_PER_REQUEST)
            continue
        scanned += 1
        # 目标日期必须正好是历史最后一天
        last_date = hist["date"].iloc[-1]
        if last_date.date() != td.date():
            time.sleep(SLEEP_PER_REQUEST)
            continue
        last = hist.iloc[-1]
        prev = hist.iloc[-2]
        chg = (last["close"] / prev["close"] - 1) * 100
        # 预筛: 目标日涨幅需 > 8.5% (万手单要求 >9.5%, 放宽一点)
        if chg <= 8.5:
            time.sleep(SLEEP_PER_REQUEST)
            continue
        # 计算完整信号
        try:
            res = compute_mytt(hist)
        except Exception as e:
            if verbose:
                print("  [calc err] %s %s: %s" % (code, name, repr(e)[:60]))
            time.sleep(SLEEP_PER_REQUEST)
            continue
        r = res.iloc[-1]
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
        if verbose and (i + 1) % 500 == 0:
            el = time.time() - t0
            print("  已扫 %d/%d | 命中: 综合%d 万手单%d 寻龙诀%d | 用时%.0fs"
                  % (i + 1, len(codes), len(combine), len(ws), len(xv), el), flush=True)
        time.sleep(SLEEP_PER_REQUEST)

    # 保存
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
    os.makedirs(out_dir, exist_ok=True)
    base = os.path.join(out_dir, "scan_%s" % target_date)
    for suffix, df_ in [("", pd.DataFrame(combine)),
                        ("_万手单", pd.DataFrame(ws)),
                        ("_寻龙诀4号", pd.DataFrame(xv))]:
        if len(df_) > 0:
            df_ = df_.sort_values("close", ascending=False).reset_index(drop=True)
            df_.to_csv(base + suffix + ".csv", index=False, encoding="utf-8-sig")
            if verbose:
                print("  已保存: %s.csv (%d 条)" % (base + suffix, len(df_)))
    if verbose:
        print("扫描完成: 有效 %d 只, 跳过 %d 只; 综合命中 %d, 万手单 %d, 寻龙诀 %d"
              % (scanned, skipped, len(combine), len(ws), len(xv)))
    return {"combined": pd.DataFrame(combine), "wanshou": pd.DataFrame(ws), "xlj4": pd.DataFrame(xv)}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("date", help="目标日期, 如 2026-09-23")
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()
    scan(args.date, start=args.start, limit=args.limit, verbose=not args.quiet)