package com.example.momentum.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.ui.theme.Blue700

/**
 * 单个文档小节
 */
data class DocSection(
    val title: String,
    val icon: ImageVector,
    val content: String,
    val items: List<String> = emptyList(),
    val apis: List<Pair<String, String>> = emptyList() // 接口名称 to URL/说明
)

/**
 * 页面说明文档模型
 */
data class TabDocData(
    val tabName: String,
    val subtitle: String,
    val version: String = "v1.2",
    val sections: List<DocSection>
)

/**
 * 通用文档底部弹窗组件 (仿 README.md 结构)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabDocSheet(
    data: TabDocData,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Color(0xFFF8FAFC)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // 头部标题与版本
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "📖 ${data.tabName} 说明文档",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Blue700
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFFDBEAFE),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = data.version,
                                color = Blue700,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = data.subtitle,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("完成", fontWeight = FontWeight.Bold, color = Blue700)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 渲染所有小节
            data.sections.forEach { section ->
                DocSectionCard(section)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DocSectionCard(section: DocSection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            // 小节标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = section.icon,
                    contentDescription = null,
                    tint = Blue700,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = section.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B)
                )
            }

            Spacer(Modifier.height(8.dp))

            // 正文说明
            if (section.content.isNotBlank()) {
                Text(
                    text = section.content,
                    fontSize = 13.sp,
                    color = Color(0xFF334155),
                    lineHeight = 20.sp
                )
            }

            // 列表项
            if (section.items.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                section.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("•", fontSize = 14.sp, color = Blue700, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = item,
                            fontSize = 12.sp,
                            color = Color(0xFF475569),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // API 接口列表
            if (section.apis.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                section.apis.forEach { (name, url) ->
                    Surface(
                        color = Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                text = "GET  $name",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Blue700
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = url,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF475569),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 预设的四大 Tab 文档内容仓库
 */
object TabDocs {

    // Tab 0: 自选股 (动量轮动)
    val WatchlistDoc = TabDocData(
        tabName = "自选股与动量轮动",
        subtitle = "基于价格动量与下行波动惩罚的量化调仓决策中心",
        sections = listOf(
            DocSection(
                title = "1. 功能概述",
                icon = Icons.Filled.Info,
                content = "专为持仓股票池打造的量化选优与定期轮动工具。针对投资者选股难、换股纠结的痛点，通过测算股票池内各标的中期价格动量并扣除波动率惩罚，自动输出「买入/持有/卖出」三档决策建议。"
            ),
            DocSection(
                title = "2. 使用方法",
                icon = Icons.Filled.TouchApp,
                content = "",
                items = listOf(
                    "添加自选：在顶部输入框输入6位代码或中文名（如\"贵州茅台\"），点击搜索候选添加至股票池；",
                    "轮动计算：点击「轮动计算」，系统基于最新收盘日K线计算复合动量分，前 Top N 只推荐买入，末尾推荐调仓卖出；",
                    "分析走势：点击「趋势分析」查看动量多头排列评分；点击「净值曲线」可模拟历史轮动回测收益率；",
                    "单股下钻：点击股票卡片可进入个股分时走势、日K与 MACD 图表；",
                    "策略参数：点击右上角齿轮图标，可调节波动率惩罚 λ、买入只数及调仓周期提醒。"
                )
            ),
            DocSection(
                title = "3. 核心量化算法",
                icon = Icons.Filled.Psychology,
                content = "经典学术界截面动量模型 (Cross-sectional Momentum)：",
                items = listOf(
                    "加权动量分：采用 20 日与 60 日收益动量加权结合：Momentum = 0.6 × R_20 + 0.4 × R_60；",
                    "波动率惩罚：对年化高波动股票施加风险扣分：Penalty = λ × Volatility_20；",
                    "综合评分：Score = Momentum - Penalty，数值越高代表涨得越平稳、持续性越强。"
                )
            ),
            DocSection(
                title = "4. 技术方案与架构",
                icon = Icons.Filled.Code,
                content = "",
                items = listOf(
                    "UI 架构：Jetpack Compose Material 3 现代化响应式声明式 UI；",
                    "本地数据库：基于 Room 数据库缓存 daily_bars，支持日线\"当日更新一次即缓存\"的高速离线兜底；",
                    "定时任务：WorkManager 周期调度 RebalanceReminderWorker，在交易日开盘前唤醒调仓通知；",
                    "灾备容错：日K线接口在遇到网络抖动或反爬限制时，自动降级至东方财富备用数据源。"
                )
            ),
            DocSection(
                title = "5. 底层 API 接口",
                icon = Icons.Filled.Api,
                content = "主要对接腾讯证券与东方财富开放行情网关：",
                apis = listOf(
                    "腾讯日K复权数据" to "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param={symbol},day,,,{days},qfq",
                    "腾讯股票搜索联想" to "https://smartbox.gtimg.cn/s3/?v=2&q={keyword}&t=gp",
                    "腾讯行情快照" to "https://qt.gtimg.cn/q={symbol}",
                    "东方财富日K备用源" to "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid={secid}&klt=101&fqt=1"
                )
            )
        )
    )

    // Tab 1: 全量暗盘
    val DarkTradeDoc = TabDocData(
        tabName = "全量暗盘交易",
        subtitle = "大宗交易与机构席位主力资金异动监控",
        sections = listOf(
            DocSection(
                title = "1. 功能概述",
                icon = Icons.Filled.Info,
                content = "全市场暗盘与大宗交易实时/盘后监控雷达。追踪机构席位的大手笔非公开筹码流转，分析主力资金建仓、对倒甩卖与折溢价信号。"
            ),
            DocSection(
                title = "2. 使用方法",
                icon = Icons.Filled.TouchApp,
                content = "",
                items = listOf(
                    "切换日期：顶部支持查看当前交易日及历史日期的全市场大单数据；",
                    "排序筛选：支持按成交额、成交量、折溢价率降序排列，筛选单笔大单龙头；",
                    "资金穿透：点击个股进入详情页，即可查看该股票近 10 日大单暗盘交易统计及主力资金净流走势。"
                )
            ),
            DocSection(
                title = "3. 核心量化算法",
                icon = Icons.Filled.Psychology,
                content = "资金异动与溢价偏离度检测：",
                items = listOf(
                    "大单门槛：单笔成交金额 ≥ 1000 万元或成交手占当日流通比例超标被识别为暗盘异动；",
                    "折溢价偏离率：Premium = (成交价 - 当日收盘价) / 当日收盘价 × 100%，高溢价多为抢筹，大额折价多为主力减持出逃。"
                )
            ),
            DocSection(
                title = "4. 技术方案与架构",
                icon = Icons.Filled.Code,
                content = "",
                items = listOf(
                    "数据流：协程异步拉取，采用限流与防反爬策略；",
                    "持久化：本地存储于 Room dark_trades 表，支持离线秒级回看；",
                    "虚拟化长列表：Compose LazyColumn 结合键值去重，支持数千条大单极速丝滑滑动。"
                )
            ),
            DocSection(
                title = "5. 底层 API 接口",
                icon = Icons.Filled.Api,
                content = "对接东方财富大宗交易与资金异动端点：",
                apis = listOf(
                    "东财大宗交易明细" to "https://datacenter-web.eastmoney.com/api/qt/data/get?type=RPT_DATA_BLOCKTRADE",
                    "东财主力资金流向" to "https://push2.eastmoney.com/api/qt/stock/fflow/kline/get?secid={secid}&klt=101"
                )
            )
        )
    )

    // Tab 2: 万龙选股
    val MyttDoc = TabDocData(
        tabName = "万龙选股 (双共振)",
        subtitle = "万手单主力建仓 AND 寻龙诀4号形态强势主升浪选股",
        sections = listOf(
            DocSection(
                title = "1. 功能概述",
                icon = Icons.Filled.Info,
                content = "专为主升浪暴涨龙头股打造的量化双共振策略。将主力资金行为学（万手特大单狂买）与经典技术形态学（寻龙诀4号突破）进行严格逻辑与运算，筛选真突破龙头。"
            ),
            DocSection(
                title = "2. 使用方法",
                icon = Icons.Filled.TouchApp,
                content = "",
                items = listOf(
                    "最佳时机：交易日 15:00 收盘后或 15:30 数据结算完毕后运行；",
                    "一键选股：点击「开始选股」，引擎自动并发同步全 A 股近期日线，并应用 Mytt 算法执行筛选；",
                    "双龙出水看板：同一股票任意两次万龙信号间隔 ≤90 天即为「双龙出水」——庄家试盘明显、近期有动作，宜结合盘面择机进场；历史沉淀信号同样计入（不要求是否今天），页面顶部以独立表格单独列出，支持一键全部加入自选；",
                    "历史信号复盘：选择不同历史日期（如 2026-10-08），回顾历史信号当天的验证表现；",
                    "导出数据：支持将选中的共振股票清单一键导出为 CSV 表格。"
                )
            ),
            DocSection(
                title = "3. 核心量化算法",
                icon = Icons.Filled.Psychology,
                content = "双共振量化公式逻辑 (Wanlong = WanShouDan ∧ XunLong4)：",
                items = listOf(
                    "因子一【万手单】：单日出现单笔委托 ≥ 10000 手的主买单，成交量较 5 日均量放大 1.8 倍以上，主力抢筹确定性高；",
                    "因子二【寻龙诀4号】：短期均线多头排列 (MA5 > MA10 > MA20)，MACD 处于零轴之上二次金叉，且突破近 20 日高点形态；",
                    "共振确认：两项条件在同日同时满足，过滤假突破与诱多震荡。",
                    "双龙出水（二次共振确认）：同一标的 90 天内二次触发双共振，表明庄家试盘动作明显、主力持续运作——近期有动作、择机进场，可靠性显著高于单次触发。"
                )
            ),
            DocSection(
                title = "4. 技术方案与架构",
                icon = Icons.Filled.Code,
                content = "",
                items = listOf(
                    "Mytt 量化引擎：Kotlin 纯原生移植的同花顺/通达信量化技术指标库 (包含 MA, CROSS, MACD, REF, HHV 等)；",
                    "高并发同步：使用 Coroutine Semaphore 限制并发连接数，既保护本地性能又防止触发服务端封控；",
                    "信号永久沉淀：Room wanlong_hits 表永久记录历史信号，随时对比后市实际涨幅。"
                )
            ),
            DocSection(
                title = "5. 底层 API 接口",
                icon = Icons.Filled.Api,
                content = "多源行情采集与全市场扫描：",
                apis = listOf(
                    "东财全A股行情池" to "https://push2.eastmoney.com/api/qt/clist/get?fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23",
                    "腾讯日K批量拉取" to "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param={symbol},day,,,60,qfq",
                    "东财备用日K通道" to "https://push2his.eastmoney.com/api/qt/stock/kline/get"
                )
            )
        )
    )

    // Tab 3: 竞价分析
    val BidDoc = TabDocData(
        tabName = "竞价分析与预测",
        subtitle = "9:15 ~ 9:25 早盘集合竞价多因子打分与胜率预测模型",
        sections = listOf(
            DocSection(
                title = "1. 功能概述",
                icon = Icons.Filled.Info,
                content = "全市场集合竞价实时多因子打分雷达。在 9:25 早盘开盘前关键时点，对全市场 5000+ 只股票的撮合价格、换手、委托量与市值进行综合建模，秒级计算上涨胜率与综合评分。"
            ),
            DocSection(
                title = "2. 使用方法",
                icon = Icons.Filled.TouchApp,
                content = "",
                items = listOf(
                    "早盘时机：9:25 集合竞价定盘后点击「刷新」，自动拉取今日竞价数据；",
                    "胜率榜单：默认按「概率」降序排列，重点观察概率 ≥ 70% 且可信度高的前排股票；",
                    "灵活筛选：展开参数面板，可选用「高胜率」模板，或勾选「剔ST」、「剔涨停」、「创业板」等过滤开关；",
                    "中文搜索：在置顶搜索框输入股票代码、中文名（如\"茅台\"）或拼音缩写（如\"gzmt\"），即时查看个股近 30 天历史竞价记录；",
                    "导出与备份：支持一键导出 CSV 报表，点击「备份」可进行全量快照归档及微信分享。"
                )
            ),
            DocSection(
                title = "3. 核心量化算法",
                icon = Icons.Filled.Psychology,
                content = "早盘集合竞价多因子线性加权回归模型：",
                items = listOf(
                    "竞价涨幅因子 (BidChange)：最佳合理区间为 +2% ~ +5%，过高防假高开诱多，低开则扣减基准分；",
                    "竞价换手率因子 (BidTurnover)：竞价爆量代表真资金进场，权重正向递增；",
                    "流通市值权重 (CircMv)：优选中轻量级题材盘子，过滤超大盘股对涨幅弹性的压制；",
                    "综合胜率 (Probability)：输出 0~100 的综合置信概率，结合评分给操盘手提供量化抓手。"
                )
            ),
            DocSection(
                title = "4. 技术方案与架构",
                icon = Icons.Filled.Code,
                content = "",
                items = listOf(
                    "9:25 快照固化：Room bid_data 表按 (code, date) 联合主键严格去重，支持离线秒查；",
                    "全量数据备份：DailyBackupManager 实现每日全量自动归档至手机 Download 公共目录；",
                    "双层智能搜索：本地 SQL 模糊匹配 + 腾讯股票服务拼音解析回退，支持中文与简拼毫秒级命中。"
                )
            ),
            DocSection(
                title = "5. 底层 API 接口",
                icon = Icons.Filled.Api,
                content = "东方财富集合竞价全市场行情与腾讯智能检索：",
                apis = listOf(
                    "东财集合竞价全量快照" to "https://push2.eastmoney.com/api/qt/clist/get?fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f12,f14,f2,f3,f4,f5,f6,f7,f8,f9,f20,f21",
                    "腾讯股票智能检索联想" to "https://smartbox.gtimg.cn/s3/?v=2&q={query}&t=gp"
                )
            )
        )
    )
}
