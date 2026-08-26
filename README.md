# 动量轮动选股助手 (Momentum Rotation Stock Picker)

一个基于**动量轮动策略**的 A 股选股辅助工具，含 Android App 与 Python 验证原型。

## 功能

- **自选股管理**：增删股票，内置示例自选池
- **自动下载数据**：腾讯前复权日线接口直连，零服务器依赖
- **动量轮动计算**：风险调整动量（Risk-Adjusted Momentum），横截面三档推荐
- **动量翻转预警**：加速 / 衰减 / 转弱信号，捕捉高位见顶风险
- **回测对比**：轮动策略 vs 等权持有 vs 各单股（累计收益 / 年化 / 最大回撤）
- **离线缓存**：Room 本地缓存，同交易日不重复下载，断网可用
- **参数设置**：观察窗口、波动率惩罚 λ、选股数量、调仓周期可调
- **调仓提醒**：WorkManager 周期通知

## 策略核心公式

```
动量分 M = Σ(wᵢ · Mᵢ) − λ·Volatility
Mᵢ = (Rᵢ − Rfᵢ) / σᵢ

Rᵢ: 过去 i 个交易日的对数收益（前复权收盘价）
σᵢ: 窗口期波动率
```

默认参数：观察窗口 **5 天**，调仓周期 **2 天**，轮动选股 **Top 3**，λ = 0.30。

## 目录结构

```
rijijndojin/
├── DESIGN.md              # 设计文档
├── momentum.py            # Python 动量评分算法(原型)
├── data_fetcher.py        # Python 数据下载(腾讯接口)
├── main.py                # Python 命令行入口
├── verify_enhance.py      # 动量翻转信号 + 回测算法验证
└── MomentumRotation/      # Android 工程(Kotlin + Jetpack Compose)
    ├── app/src/main/java/com/example/momentum/
    │   ├── MainActivity.kt
    │   ├── model/         # 数据模型
    │   ├── engine/        # 动量引擎 / 翻转预警 / 回测
    │   ├── data/          # 数据下载 + Room 缓存 + 设置
    │   ├── notify/        # WorkManager 调仓提醒
    │   └── ui/            # Compose UI
    ├── gradle/            # 版本目录 + wrapper
    └── build.gradle.kts
```

## 构建 APK

```bash
cd MomentumRotation
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 打开 `MomentumRotation/` 目录。

## 运行 Python 原型

```bash
pip install akshare pandas numpy requests
python3 main.py                        # 内置示例自选股
python3 main.py 603991 300285 600183   # 指定自选股代码
```

## 技术栈

| 层 | 技术 |
|----|------|
| Android UI | Kotlin + Jetpack Compose (Material 3) |
| 数据 | Room + DataStore + 腾讯 ifzq 接口(HttpURLConnection) |
| 后台任务 | WorkManager |
| 原型 | Python 3 + pandas + numpy + akshare |

## 免责声明

本工具仅供学习与技术研究，不构成任何投资建议。动量轮动策略在震荡市可能跑输，历史回测不代表未来收益。股市有风险，投资需谨慎。
