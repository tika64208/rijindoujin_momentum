# -*- coding: utf-8 -*-
"""
run.py —— MYTT 选股器 启动入口
=============================
用法(在本目录下):
    python3 run.py

在交易日收盘后(建议 16:00 以后)运行, 程序会:
  1. 取全市场 A股代码
  2. 用当日快照把候选缩到“今日大涨、低价”的少量股票
  3. 逐只抓取数年日线, 计算“万手单 AND 寻龙诀4号”信号
  4. 把命中的股票打印出来并保存成 CSV

打印格式:
  命中: 600519 贵州茅台 日期=2025-01-02 收盘=1690.00
"""
import sys
import os

# 确保 import 能找到同目录的模块
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from selector import run_select
from config import PRINT_TOP_N


def _print_block(title, df):
    print(title)
    print("-" * 60)
    if df is not None and len(df) > 0:
        for i, r in df.head(PRINT_TOP_N).iterrows():
            print("  %-6s %-10s 收盘=%-9s 日期=%s  (信号:%s)"
                  % (r["code"], str(r["name"]), str(r["close"]), r["date"],
                     r.get("触发信号", "万手单 AND 寻龙诀4号")))
        if len(df) > PRINT_TOP_N:
            print("  ... 其余 %d 只省略, 详见 CSV" % (len(df) - PRINT_TOP_N))
    else:
        print("  (无)")
    print()


def main():
    print("=" * 60)
    print("  MYTT 选股器   ——  万手单 AND 寻龙诀4号")
    print("=" * 60)
    res = run_select(verbose=True)
    print()
    print("=" * 60)
    if isinstance(res, dict):
        _print_block("【综合】万手单 AND 寻龙诀4号 双信号命中:", res["combined"])
        _print_block("【万手单】单独命中:", res["wanshou"])
        _print_block("【寻龙诀4号】单独命中:", res["xlj4"])
        if len(res["combined"]) == 0:
            print("说明: 今日没有同时满足两个策略的股票。")
            print("      “万手单”或“寻龙诀4号”各自命中, 详见上方分表/CSV。")
            print("      可能原因: 当日无涨停附近低价股 / 数据未更新 / 或确实无此形态。")
    else:
        # 兼容旧返回(单表)
        _print_block("【综合】万手单 AND 寻龙诀4号 双信号命中:", res)
    print("=" * 60)


if __name__ == "__main__":
    main()