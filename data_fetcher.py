"""
A股数据下载模块 (双数据源容错)
================================
主源: 腾讯 ifzq 复权 K 线接口 (支持前复权 qfq)
备源: 新浪日线接口 (不复权, 供应急)

返回标准 DataFrame: 列 [date, open, high, low, close, volume], 按日期升序。
注意: 腾讯 qfqday 字段顺序为 [date, open, close, high, low, volume], 需注意转置。

复权说明: 动量计算必须用前复权价, 否则除权除息会产生假动量。
腾讯接口直接返回前复权(qfq)序列, 无需自行复权。
"""
from __future__ import annotations

import datetime as dt
import time
from typing import Optional

import pandas as pd
import requests

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)


def _market_prefix(code: str) -> str:
    """6位代码 -> 带市场前缀 (sh/sz)。"""
    if code.startswith(("6", "9", "5")):
        return "sh" + code
    if code.startswith(("0", "2", "3")):
        return "sz" + code
    if code.startswith(("4", "8")):
        return "bj" + code
    return "sh" + code


# ---------------------------------------------------------------------------
# 主源: 腾讯复权接口
# ---------------------------------------------------------------------------
def _fetch_tx_qfq(code: str, days: int) -> pd.DataFrame:
    """腾讯前复权日线。返回标准 DataFrame (可为空)。"""
    sym = _market_prefix(code)
    url = (
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
        f"?param={sym},day,,,{days},qfq"
    )
    h = {"User-Agent": UA, "Referer": "https://gu.qq.com/"}
    r = requests.get(url, headers=h, timeout=15)
    r.raise_for_status()
    data = r.json()
    node = data.get("data", {}).get(sym, {})
    rows = node.get("qfqday") or node.get("day") or []
    if not rows:
        return pd.DataFrame()

    # 腾讯字段顺序: [date, open, close, high, low, volume]
    df = pd.DataFrame(rows, columns=["date", "open", "close", "high", "low", "volume"])
    df = df[["date", "open", "high", "low", "close", "volume"]]
    df["date"] = pd.to_datetime(df["date"])
    for c in ["open", "high", "low", "close", "volume"]:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    df = df.dropna(subset=["close"]).sort_values("date").reset_index(drop=True)
    return df


# ---------------------------------------------------------------------------
# 备源: 新浪不复权接口 (应急, 复权信息缺失, 不建议用于动量)
# ---------------------------------------------------------------------------
def _fetch_sina(code: str, days: int) -> pd.DataFrame:
    sym = _market_prefix(code)
    url = (
        "https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_=/CN_MarketDataService.getKLineData"
        f"?symbol={sym}&scale=240&ma=no&datalen={days}"
    )
    h = {"User-Agent": UA, "Referer": "https://finance.sina.com.cn"}
    r = requests.get(url, headers=h, timeout=15)
    r.raise_for_status()
    text = r.text
    # 剥离 jsonp 包装: var _=([...]);
    start = text.find("([")
    end = text.rfind("])")
    if start == -1 or end == -1:
        return pd.DataFrame()
    body = text[start + 1 : end + 1]
    import json

    rows = json.loads(body)
    if not rows:
        return pd.DataFrame()
    df = pd.DataFrame(rows)
    df = df.rename(columns={"day": "date"})
    df = df[["date", "open", "high", "low", "close", "volume"]]
    df["date"] = pd.to_datetime(df["date"])
    for c in ["open", "high", "low", "close", "volume"]:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    df = df.dropna(subset=["close"]).sort_values("date").reset_index(drop=True)
    return df


# ---------------------------------------------------------------------------
# 统一入口
# ---------------------------------------------------------------------------
def get_stock_daily(code: str, years: int = 2, retries: int = 3) -> pd.DataFrame:
    """下载 A股前复权日线, 主源腾讯 + 备源新浪, 带重试。"""
    days = int(250 * years) + 30
    last_err: Optional[Exception] = None

    for src in ("tx", "sina"):
        for attempt in range(retries):
            try:
                if src == "tx":
                    df = _fetch_tx_qfq(code, days)
                else:
                    df = _fetch_sina(code, days)
                if not df.empty:
                    return df
            except Exception as e:  # noqa: BLE001
                last_err = e
                time.sleep(0.8 * (attempt + 1))
    if last_err:
        raise RuntimeError(f"{code} 数据下载失败: {last_err}")
    return pd.DataFrame()


def get_stock_name(code: str) -> str:
    """股票名称(腾讯实时接口, 失败返回代码)。"""
    sym = _market_prefix(code)
    url = f"https://qt.gtimg.cn/q={sym}"
    h = {"User-Agent": UA}
    try:
        r = requests.get(url, headers=h, timeout=8)
        r.raise_for_status()
        # 返回形如 v_sh600519="1~贵州茅台~600519~...~";
        text = r.text.split("~")
        if len(text) > 1:
            return "".join(text[1].split())
    except Exception:  # noqa: BLE001
        pass
    return code
