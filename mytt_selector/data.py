# -*- coding: utf-8 -*-
"""
data.py —— 行情与候选股的获取
==============================
- 取全市场 A 股代码/名称列表
- 取当日行情快照(新浪), 用于快速预筛候选股
- 对候选股逐只抓取数年日线历史(新浪), 并缓存到本地 disk
  (当天数据只抓一次, 之后直接读缓存, 避免被限流)

对外主要函数:
    exchange_prefix(code)       代码 -> sh/sz/bj 前辍
    get_stock_list()            返回 DataFrame[code,name]
    fetch_snapshot()            返回 DataFrame(全市场当日快照)
    prefetch_candidates()       按条件预筛出少量候选股
    load_daily(symbol)          返回该 symbol 的日线 DataFrame(含缓存)
"""
import os
import time
import threading

import numpy as np
import pandas as pd

# akshare 的部分接口偶发断连, 做几层重试
RETRY = 3

_CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "daily")
_meta_lock = threading.Lock()

# parquet 引擎(若未安装则退化为 csv)
try:
    import pyarrow  # noqa: F401
    _PARQUET_OK = True
except Exception:
    try:
        import fastparquet  # noqa: F401
        _PARQUET_OK = True
    except Exception:
        _PARQUET_OK = False


def _cache_save(df, path):
    if _PARQUET_OK:
        try:
            df.to_parquet(path)
            return
        except Exception:
            pass
    df.to_csv(path.replace(".parquet", ".csv"), index=False)


def _cache_load(path):
    p = path
    if _PARQUET_OK and os.path.exists(p):
        return pd.read_parquet(p)
    c = p.replace(".parquet", ".csv")
    if os.path.exists(c):
        return pd.read_csv(c)
    return None


def _retry(fn, name, tries=RETRY):
    last = None
    for i in range(tries):
        try:
            return fn()
        except Exception as e:  # 网络抖动
            last = e
            wait = 1.0 * (i + 1)
            time.sleep(wait)
    raise last


def exchange_prefix(code: str) -> str:
    """根据 A股代码返回交易所代码。6 开头→sh；0/3 开头→sz；其余(8/4/9)→bj。"""
    code = str(code).zfill(6)
    if code.startswith("6"):
        return "sh"
    if code.startswith(("0", "3")):
        return "sz"
    return "bj"


def get_stock_list():
    """返回 DataFrame[code,name]，全市场 A 股。带缓存。"""
    cache = os.path.join(_CACHE_DIR, "..", "..", "data", "stock_list.parquet")
    cache = os.path.normpath(cache)
    os.makedirs(os.path.dirname(cache), exist_ok=True)
    if os.path.exists(cache) or os.path.exists(cache.replace(".parquet", ".csv")):
        try:
            df = _cache_load(cache)
            if df is not None and len(df) > 1000:
                return df
        except Exception:
            pass
    import akshare as ak
    df = _retry(lambda: ak.stock_info_a_code_name(), "stock_list")
    df = df[["code", "name"]].copy()
    df["code"] = df["code"].astype(str).str.zfill(6)
    try:
        _cache_save(df, cache)
    except Exception:
        pass
    return df


def fetch_snapshot():
    """全市场当日行情快照(新浪)。列含 代码/名称/最新价/涨跌幅/昨收 等。"""
    import akshare as ak
    df = _retry(lambda: ak.stock_zh_a_spot(), "snapshot")
    return df


def prefetch_candidates(snapshot=None, min_chg_pct=8.5, max_price=50.0):
    """
    基于当日快照, 预筛出候选股(需满足今日大涨、低价等粗略条件)。
    返回 DataFrame[code, symbol, name, ...]。
    注意: 这只是一个快速预筛, 最终是否命中由完整公式决定。
    """
    if snapshot is None:
        snapshot = fetch_snapshot()
    # 新浪快照列: 代码(如 sz000001), 名称, 最新价, 涨跌额, 涨跌幅, 昨收...
    df = snapshot.copy()
    df = df.rename(
        columns={
            "代码": "raw_symbol",
            "名称": "name",
            "最新价": "last",
            "昨收": "prev_close",
            "涨跌幅": "pct_chg",
        }
    )
    # 只保留沪深(sh/sz), 剔除北交所(bj)
    df = df[df["raw_symbol"].str.startswith(("sh", "sz"))].copy()
    df["code"] = df["raw_symbol"].str.replace(r"^(sh|sz)", "", regex=True)
    df["pct_chg"] = pd.to_numeric(df["pct_chg"], errors="coerce")
    df["last"] = pd.to_numeric(df["last"], errors="coerce")
    # 剔除 ST、新股名中带 N
    df = df[~df["name"].astype(str).str.contains("ST", na=False)]
    # 预筛条件(宽松一点, 以免漏掉边界信号)
    df = df[df["pct_chg"] > min_chg_pct]
    df = df[(df["last"] < max_price * 1.5) | df["last"].isna()]
    df = df.reset_index(drop=True)
    return df


def load_daily(symbol: str, start_date: str = "20210101", end_date: str = None, adjust: str = "qfq"):
    """
    读取某只股票(symbol 形如 sh600000)的日线历史。
    带磁盘缓存：缓存存在且覆盖到今天则直接读缓存。
    返回按日期升序的 DataFrame，列: date, open, high, low, close, volume, amount,
    outstanding_share, turnover。行索引为位置索引。
    """
    if end_date is None:
        end_date = time.strftime("%Y%m%d")
    os.makedirs(_CACHE_DIR, exist_ok=True)
    fname = "%s_%s.parquet" % (symbol, adjust)
    path = os.path.join(_CACHE_DIR, fname)

    if os.path.exists(path) or os.path.exists(path.replace(".parquet", ".csv")):
        try:
            df = _cache_load(path)
            if df is not None:
                df["date"] = pd.to_datetime(df["date"])
                # 若缓存已含到今天, 直接用
                last_cache = df["date"].max()
                today = pd.to_datetime(end_date)
                if (last_cache.year == today.year and last_cache.month == today.month
                        and last_cache.day >= today.day - 1):
                    return df
        except Exception:
            pass  # 缓存损坏或过期, 继续重新抓取
        # 退出 if 后 path 仍保留; 若 CSV 缓存存在也会被重新覆盖

    import akshare as ak
    df = _retry(lambda: ak.stock_zh_a_daily(
        symbol=symbol, start_date=start_date, end_date=end_date, adjust=adjust,
    ), "daily:" + symbol)
    df = df.rename(columns={"date": "date"}).copy()
    df["date"] = pd.to_datetime(df["date"])
    df = df.sort_values("date").reset_index(drop=True)
    try:
        os.makedirs(_CACHE_DIR, exist_ok=True)
        if path is not None:
            _cache_save(df, path)
    except Exception:
        pass
    return df