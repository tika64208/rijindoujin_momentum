# 动量轮动选股助手 (Momentum Rotation Stock Picker)

> 目标平台：Android（小米等现代 Android 机型，支持 Android 8.0+ / API 26+）  
> 标的市场：A 股（沪市、深市、创业板、科创板、北交所）  
> 核心理念：**全端侧端到端计算、零自有服务器依赖**，直连金融公开行情接口，手机本地实时量化选股与决策辅助。

---

## 📖 项目定位与简介

本项目是一套完整的端侧 A 股量化决策辅助系统，由 **Python 量化研究原型** 与 **Android 原生客户端 (Kotlin + Jetpack Compose)** 构成。

系统不依赖任何自建云端后端，所有行情数据均在端侧通过防爬伪装直连腾讯、东方财富、同花顺、新浪等公开数据源，在手机本地完成增量缓存、通达信公式虚拟机解析、多周期风险调整动量打分、早盘竞价五因子胜率推算及全量组合回测。

---

## 🚀 四大核心功能模块 (主 Tab 导航)

### 1. 自选股与动量轮动 (`MomentumScreen`)
- **风险调整动量模型 (Risk-Adjusted Momentum)**：
  $$M = \sum (w_i \cdot M_i) - \lambda \cdot \text{Volatility}, \quad M_i = \frac{R_i - Rf_i}{\sigma_i}$$
  通过对近期收益除以波动率 $\sigma$，惩罚暴涨暴跌的妖股，奖励稳健上涨的高夏普标的。
- **横截面信号分档**：自选池内按动量分降序分为 **🟢买入/加仓 (Top 20%)**、**🟡持有/观察 (Mid 60%)** 与 **🔴卖出/减仓 (Bottom 20%)**。
- **动量翻转/衰减预警**：监测动量加速度与转弱信号，提示高位见顶风险。
- **动态权益曲线回测 (`EquityCurveEngine`)**：支持对自选池历史表现进行真实调仓回测，展示轮动策略 vs 等权持有 vs 各单股的历史净值曲线、累计收益率、年化收益与最大回撤。
- **调仓提醒**：基于 Android WorkManager 定时触发调仓提醒通知。

### 2. 全量暗盘异动分析 (`DarkTradeListScreen`)
- **大单与大宗交易监控**：实时追踪全市场大额暗盘交易与主力隐秘买卖行为。
- **智能研判引擎 (`DarkTradeAnalyzer`)**：解析个股主力净流向、连续净买入/净卖出天数；自动识别超近期均值 3 倍以上的异常放量吸筹信号。

### 3. MYTT 通达信/麦语言量化选股 (`MyttScreen`)
- **纯 Kotlin 通达信公式虚拟机 (`Talc.kt`)**：不依赖通达信客户端，原生实现了 `MA`、`EMA`、`SMA`、`REF`、`CROSS`、`BARSLAST`、`HHV`、`LLV` 等经典技术分析函数。
- **策略模型 (`MyttEngine.kt`)**：
  - **万手单策略**：捕捉主力异常大单建仓异动。
  - **寻龙诀 4 号策略**：趋势放量主升起爆模型。
  - **双共振选股**：万手单与寻龙诀 4 号信号在窗口期内同时触发，全市场量化扫描。

### 4. 早盘集合竞价量化系统 (`BidAnalysisScreen` V3.2)
- **聚焦 9:15 - 9:25 早盘集合竞价**：实时捕捉主力抢筹与异动特征。
- **五因子涨停概率与 V2 评分 (`BidStrategy.kt`)**：综合开盘涨幅、竞价换手率、流通市值、异动标志及概念热度打分 (0~100) 并预估涨停胜率。
- **盘中实时快照继承**：早盘 9:25 生成竞价快照，盘中刷新时动态继承早盘竞价封单/换手特征并结合盘中现价穿透。
- **CSV 导出与每日双重备份 (`DailyBackupManager`)**：支持一键导出 CSV，每日自动将竞价数据归档备份至公共存储 (`Download/MomentumRotation/daily_backup/`) 及应用内部沙盒。

### 5. 个股全维度深度穿透 (`StockDetailScreen`)
- **分时走势对照**：叠加对应大盘指数（上证/深证/创业板/科创50）走势基准。
- **技术指标图**：日 K 线与均线系统、MACD 动态指标（DIF/DEA/红绿柱）。
- **资金流向拆解**：超大单、大单、中单、小单主力净流入动态图。
- **行业板块联动**：展示同花顺所属行业与概念板块走势。

---

## 📂 项目工程目录结构

```
rijijndojin/
├── README.md                      # 项目总体架构与使用说明（本文档）
├── DESIGN.md                      # 策略模型数学推导与架构设计文档
│
├── Python 研究原型与验证脚本
│   ├── data_fetcher.py            # 腾讯 ifzq 前复权日线接口抓取
│   ├── momentum.py                # 动量评分核心算法与推荐映射（纯算法）
│   ├── main.py                    # 命令行测试与自选股评分入口
│   ├── verify_enhance.py          # 动量翻转预警与策略回测检验脚本
│   └── verify_curve.py            # 权益曲线计算与收益对比复核
│
├── MomentumRotation/              # 主 Android 原生工程 (Kotlin + Jetpack Compose)
│   ├── app/src/main/java/com/example/momentum/
│   │   ├── MainActivity.kt        # 应用入口、四大 Tab 底部导航、全屏加载进度条
│   │   │
│   │   ├── model/                 # 核心数据模型
│   │   │   ├── Stock.kt           # 股票基本信息、日K线 (DailyBar)、自选池定义
│   │   │   └── Momentum.kt        # 动量得分、推荐档位、回测统计结果
│   │   │
│   │   ├── engine/                # 纯 Kotlin 量化策略计算引擎（无 Android SDK 依赖）
│   │   │   ├── MomentumEngine.kt      # 多周期风险调整动量打分与截面排名
│   │   │   ├── MomentumTrendEngine.kt # 动量趋势追踪、加速/衰减/转弱预警
│   │   │   ├── BacktestEngine.kt      # 轮动策略 vs 等权持有 vs 单股历史回测
│   │   │   ├── EquityCurveEngine.kt   # 组合净值曲线与最大回撤计算
│   │   │   ├── BidStrategy.kt         # 早盘竞价五因子涨停胜率与 V2 量化评分模型
│   │   │   ├── MyttEngine.kt          # 万手单 + 寻龙诀 4 号选股计算
│   │   │   ├── Talc.kt                # 通达信/麦语言函数库 (SMA, REF, CROSS 等)
│   │   │   ├── DarkTradeAnalyzer.kt   # 暗盘交易与主力吸筹智能研判
│   │   │   ├── MacdEngine.kt          # MACD 趋势指标算法
│   │   │   ├── MonitorEngine.kt       # 盘中异常监测引擎
│   │   │   └── StrategyConfig.kt      # 策略参数配置数据类
│   │   │
│   │   ├── data/                  # 数据层与仓储
│   │   │   ├── net/
│   │   │   │   └── HttpExecutor.kt    # 统一 HTTP 请求执行器（防爬伪装、Header 注入、容灾降级）
│   │   │   ├── db/                    # Room 本地持久化缓存
│   │   │   │   ├── AppDatabase.kt     # Room Database 定义
│   │   │   │   ├── Entities.kt        # 数据库实体（DailyBarEntity, DarkTradeEntity, BidEntity）
│   │   │   │   ├── EntityMappers.kt   # Entity 与 Domain Model 转换映射
│   │   │   │   ├── DailyBarDao.kt     # 日线 K 线 DAO
│   │   │   │   ├── DarkTradeDao.kt    # 暗盘交易 DAO
│   │   │   │   └── BidDao.kt          # 竞价分析数据 DAO
│   │   │   ├── TencentDataSource.kt   # 腾讯 ifzq 前复权日线与分时数据源
│   │   │   ├── EastmoneyBidDataSource.kt # 东方财富 9:25 竞价数据源
│   │   │   ├── DarkTradeDataSource.kt # 东方财富全量暗盘/大宗数据源
│   │   │   ├── MoneyFlowDataSource.kt # 东方财富主力资金流向数据源
│   │   │   ├── MainFundDataSource.kt  # 主力资金数据源
│   │   │   ├── MarginDataSource.kt    # 融资融券数据源
│   │   │   ├── ThsDataSource.kt       # 同花顺行业/概念板块数据源
│   │   │   ├── SinaDataSource.kt      # 新浪实时行情兜底源
│   │   │   ├── AStockDataSource.kt    # 全市场股票列表数据源
│   │   │   ├── AStockDownloader.kt    # 股票代码与基础信息同步器
│   │   │   ├── StockRepository.kt     # 自选股与日线数据仓储
│   │   │   ├── DarkTradeRepository.kt # 暗盘数据仓储
│   │   │   ├── BidRepository.kt       # 竞价数据仓储（含快照继承机制）
│   │   │   └── SettingsRepository.kt  # DataStore 用户策略偏好设置
│   │   │
│   │   ├── notify/                # 后台定时调度与通知
│   │   │   ├── RebalanceReminderWorker.kt # 周期性调仓提醒 Worker
│   │   │   └── MonitorWorker.kt       # 盘中监控 Worker
│   │   │
│   │   ├── ui/                    # UI 与可视化交互层
│   │   │   ├── screen/                # 页面与 ViewModel
│   │   │   │   ├── MomentumScreen.kt      # Tab 1: 自选股动量轮动页
│   │   │   │   ├── MomentumViewModel.kt
│   │   │   │   ├── DarkTradeListScreen.kt # Tab 2: 全量暗盘列表页
│   │   │   │   ├── DarkTradeListViewModel.kt
│   │   │   │   ├── MyttScreen.kt          # Tab 3: MYTT 选股页
│   │   │   │   ├── MyttViewModel.kt
│   │   │   │   ├── BidAnalysisScreen.kt   # Tab 4: 集合竞价分析页
│   │   │   │   ├── BidAnalysisViewModel.kt
│   │   │   │   ├── StockDetailScreen.kt   # 个股深度详情页
│   │   │   │   ├── SettingsScreen.kt      # 参数设置与备份管理页
│   │   │   │   ├── DownloadViewModel.kt   # 全局行情下载状态控制器
│   │   │   │   ├── KLineChart.kt          # 日 K 线图绘制组件
│   │   │   │   ├── MacdChart.kt           # MACD 趋势副图组件
│   │   │   │   ├── MoneyFlowChart.kt      # 主力资金流向图组件
│   │   │   │   ├── EquityCurvePanel.kt    # 回测权益曲线图板
│   │   │   │   ├── AnalysisPanels.kt      # 动量评分与预警卡片
│   │   │   │   ├── DarkTradePanel.kt      # 暗盘交易诊断卡片
│   │   │   │   └── BoardPanel.kt          # 板块成分卡片
│   │   │   └── theme/                 # 主题与配色
│   │   │       └── Theme.kt               # 金融深色主题 (Dark Theme)
│   │   │
│   │   └── util/                  # 通用工具
│   │       ├── DailyBackupManager.kt  # 每日竞价 CSV 自动双重备份管理
│   │       ├── DataExporter.kt        # CSV 格式化导出与分享
│   │       └── FinanceFormatter.kt    # 市值、成交量、涨跌幅格式化工具
│   │
│   └── app/src/test/              # 单元测试（JUnit 验证量化计算引擎正确性）
│       ├── MomentumEngineTest.kt
│       ├── EquityCurveEngineTest.kt
│       ├── MomentumTrendEngineTest.kt
│       └── TencentDataSourceTest.kt
│
├── mytt_selector/                 # [历史验证模块] MYTT 独立 Python 选股原型脚本
└── mytt_android/                  # [历史验证模块] 早期独立轻量 APK 原型（已完整融合进主工程）
```

---

## 🛠️ 构建与运行

### 1. 构建 Android 原生客户端

在开发机上安装有 JDK 17+ 和 Android SDK：

```bash
cd MomentumRotation

# 运行全量单元测试
./gradlew test

# 编译 Debug 版 APK
./gradlew :app:assembleDebug
# 产物路径: MomentumRotation/app/build/outputs/apk/debug/app-debug.apk

# 编译 Release 版 APK (已配置复用本地调试签名，可直接安装测试)
./gradlew :app:assembleRelease
# 产物路径: MomentumRotation/app/build/outputs/apk/release/app-release.apk
```

或直接在 Android Studio 中打开 `MomentumRotation/` 目录进行调试与运行。

### 2. 运行 Python 原型与验证脚本

用于快速验证算法与策略回测：

```bash
pip install akshare pandas numpy requests

# 运行动量轮动打分原型
python3 main.py

# 指定股票代码计算动量
python3 main.py 603991 300285 600183

# 运行动量翻转预警与回测检验
python3 verify_enhance.py
```

---

## 📊 技术栈选型

| 维度 | 技术栈 | 说明 |
|------|--------|------|
| **UI 框架** | Kotlin + Jetpack Compose + Material 3 | 现代化声明式 UI，深色金融级行情配色，全屏 Edge-to-Edge 适配 |
| **异步与流式** | Kotlin Coroutines + StateFlow / SharedFlow | 响应式状态管理，盘中异步刷新无卡顿 |
| **本地存储** | Room (SQLite) + Jetpack DataStore | 日线数据/暗盘/竞价强类型持久化，断网离线可用，同交易日增量获取 |
| **网络层** | 原生 HttpURLConnection + 自定义伪装层 (`HttpExecutor`) | 注入定制 UA 与 Referer，穿透各大财经站点反爬，零额外第三方网络库依赖 |
| **后台调度** | Android Jetpack WorkManager | 周期性盘中监测与尾盘/开盘调仓推送通知 |
| **公式引擎** | 纯 Kotlin 自研麦语言虚拟机 (`Talc`) | 移植通达信经典指标计算体系，支持任意复杂技术指标 |
| **研究原型** | Python 3 + pandas + numpy + akshare | 算法原型设计、参数搜索与策略对照 |

---

## ⚠️ 免责声明

1. 本软件及相关代码仅用于**个人技术研究与量化策略学习**，不构成任何投资建议或交易指引。
2. 动量轮动、集合竞价胜率及技术指标均为历史数据统计推算，股市有风险，投资需谨慎。
3. 数据均来源于各大财经网站的公开网络接口，使用者需遵守相关数据源的服务条款。
