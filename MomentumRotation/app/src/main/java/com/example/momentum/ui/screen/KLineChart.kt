package com.example.momentum.ui.screen

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.model.DailyBar
import com.example.momentum.ui.theme.*

/** A股配色: 红涨绿跌 */
private val Rise = Color(0xFFDC2626)
private val Fall = Color(0xFF16A34A)
// ===== 吞金起爆主图指标配色 (通达信公式移植, 浅色背景适配) =====
private val QipaoColor = Color(0xFFDC2626)   // 吞金起爆线: COLORRED
private val AttackColor = Color(0xFF1E293B)  // 主力攻击线: COLORWHITE → 浅底改深灰
private val SlowColor = Color(0xFFCA8A04)    // 吞金杀牛线: COLORYELLOW → 浅底改金黄
private val StrongColor = Color(0xFFF59E0B)  // 走强线: COLORYELLOW → 亮琥珀虚线(与杀牛线区分)

/** 简单移动平均线计算(不足N天返回 null)。 */
private fun maValues(bars: List<DailyBar>, period: Int): List<Double?> {
    val closes = bars.map { it.close }
    return bars.indices.map { i ->
        if (i + 1 < period) null else closes.subList(i + 1 - period, i + 1).average()
    }
}

/** 多条序列逐位取平均(任一位为 null 则该位为 null) — 复合均线 X_29/X_30 用。 */
private fun avgOf(vararg series: List<Double?>): List<Double?> =
    series[0].indices.map { i ->
        val vals = series.mapNotNull { it.getOrNull(i) }
        if (vals.size == series.size) vals.average() else null
    }

// ===== 通达信函数移植辅助 (吞金起爆全套指标用) =====

/** EMA 递归指数均线 (头部 NaN 跳过, 首个有效值作种子, TDX EMA 语义)。 */
private fun emaD(x: List<Double>, n: Int): List<Double> {
    val a = 2.0 / (n + 1)
    val out = ArrayList<Double>(x.size)
    var prev = Double.NaN
    for (v in x) {
        prev = when {
            prev.isNaN() -> v
            else -> a * v + (1 - a) * prev
        }
        out.add(prev)
    }
    return out
}

/** MA 简单均线 (NaN 传播)。 */
private fun maD(x: List<Double>, n: Int): List<Double> = x.indices.map { i ->
    if (i + 1 < n) Double.NaN
    else {
        val w = x.subList(i + 1 - n, i + 1)
        if (w.any { it.isNaN() }) Double.NaN else w.average()
    }
}

private fun refD(x: List<Double>, n: Int): List<Double> =
    x.indices.map { i -> if (i - n >= 0) x[i - n] else Double.NaN }

private fun hhvD(x: List<Double>, n: Int): List<Double> = x.indices.map { i ->
    val w = x.subList(maxOf(0, i - n + 1), i + 1)
    if (w.any { it.isNaN() }) Double.NaN else w.max()
}

private fun llvD(x: List<Double>, n: Int): List<Double> = x.indices.map { i ->
    val w = x.subList(maxOf(0, i - n + 1), i + 1)
    if (w.any { it.isNaN() }) Double.NaN else w.min()
}

private fun countC(c: List<Boolean>, n: Int): List<Int> = c.indices.map { i ->
    (maxOf(0, i - n + 1)..i).count { c[it] }
}

private fun crossD(a: List<Double>, b: List<Double>): List<Boolean> =
    a.indices.map { i -> i > 0 && a[i - 1] <= b[i - 1] && a[i] > b[i] }

/** TDX FILTER: 信号后 n 周期内屏蔽后续信号。 */
private fun filterC(c: List<Boolean>, n: Int): List<Boolean> {
    val out = ArrayList<Boolean>(c.size)
    var sup = -1
    for (i in c.indices) {
        val ok = c[i] && i > sup
        out.add(ok)
        if (ok) sup = i + n
    }
    return out
}

/** TDX LAST(X,A,B): X 连续成立 A 个周期, 截止 B 个周期前。 */
private fun lastC(c: List<Boolean>, a: Int, b: Int): List<Boolean> = c.indices.map { i ->
    (b until b + a).all { k -> i - k >= 0 && c[i - k] }
}

/**
 * COST(99) 近似: 逐日把成交量均匀摊进当日 [low,high] 价格区间, 旧筹码按时间衰减
 * (每日 ×0.99, 近似换手衰减 —— TDX 原版依赖流通股本数据, 日线OHLCV无法精确复刻),
 * 自低向高累计 99% 处的价格即 99% 成本线。
 */
private fun cost99Series(bars: List<DailyBar>, decay: Double = 0.99, buckets: Int = 256): List<Double> {
    if (bars.isEmpty()) return emptyList()
    val lo = bars.minOf { it.low }
    val hi = bars.maxOf { it.high }
    if (!(hi > lo)) return bars.map { it.close }
    val w = (hi - lo) / buckets
    val dist = DoubleArray(buckets)
    val out = ArrayList<Double>(bars.size)
    for (b in bars) {
        for (j in dist.indices) dist[j] *= decay
        val b0 = ((b.low - lo) / w).toInt().coerceIn(0, buckets - 1)
        val b1 = ((b.high - lo) / w).toInt().coerceIn(0, buckets - 1)
        val cnt = b1 - b0 + 1
        val per = b.volume / cnt
        for (j in b0..b1) dist[j] += per
        var total = 0.0
        for (v in dist) total += v
        var price = b.close
        if (total > 0) {
            var acc = 0.0
            for (j in dist.indices) {
                acc += dist[j]
                if (acc >= 0.99 * total) { price = lo + (j + 1) * w; break }
            }
        }
        out.add(price)
    }
    return out
}

/** 吞金起爆全套主图指标计算结果 (全序列, 与 bars 等长)。 */
private data class QipaoIndicators(
    val qipao: List<Double?>,      // 吞金起爆线: MA20+MA20*18/1000.35
    val attack: List<Double?>,      // 主力攻击线 X_29
    val slow: List<Double?>,        // 吞金杀牛线 X_30
    val conf: List<Boolean>,        // CONF := X_29>REF(X_29,1) AND X_29>X_30
    val strong: List<Double>,       // 走强线: EMA(COST99近似, 181)
    val sell: List<Boolean>,        // 卖 (DX 顶部信号, FILTER 1)
    val buy: List<Boolean>,         // 买1 ★ (DX 底部信号, FILTER 5)
    val zhuangJin: List<Boolean>,   // ↖庄进 (FILTER 30)
    val zhuangChu: List<Boolean>,   // ↓庄出
    val bigDrop: List<Boolean>      // 大跌日: C<0.905*REF(C,1) 画绿棒
)

/** 计算吞金起爆全套指标 (通达信公式 1:1 移植; DYNAINFO(11) 用当日OHLC均值近似)。 */
private fun computeQipao(bars: List<DailyBar>): QipaoIndicators {
    val n = bars.size
    val closes = bars.map { it.close }
    // ---- 吞金起爆 / 攻击线 / 杀牛线 / CONF ----
    val ma3 = maValues(bars, 3); val ma5 = maValues(bars, 5); val ma6 = maValues(bars, 6)
    val ma9 = maValues(bars, 9); val ma10 = maValues(bars, 10); val ma15 = maValues(bars, 15)
    val ma20 = maValues(bars, 20); val ma25 = maValues(bars, 25); val ma30 = maValues(bars, 30)
    val qipao = ma20.map { v -> v?.let { it + it * 18.0 / 1000.35 } }
    val attack = avgOf(ma3, ma6, ma9)
    val slow = avgOf(ma5, ma10, ma15, ma20, ma25, ma30)
    val conf = bars.indices.map { i ->
        val a = attack[i]
        val ap = if (i > 0) attack[i - 1] else null
        val s = slow[i]
        a != null && ap != null && s != null && a > ap && a > s
    }
    // ---- 走强线: EMA(COST(99)近似, 181) ----
    val strong = emaD(cost99Series(bars), 181)
    // ---- DX 动向: 100*EMA(EMA(MTM,6),6)/EMA(EMA(|MTM|,6),6) ----
    val mtm = closes.indices.map { i -> if (i >= 1) closes[i] - closes[i - 1] else Double.NaN }
    val absMtm = mtm.map { if (it.isNaN()) Double.NaN else kotlin.math.abs(it) }
    val dxNum = emaD(emaD(mtm, 6), 6)
    val dxDen = emaD(emaD(absMtm, 6), 6)
    val dx = closes.indices.map { i ->
        val d = dxDen[i]
        if (d.isNaN() || d == 0.0) Double.NaN else 100.0 * dxNum[i] / d
    }
    val maDx2 = maD(dx, 2)
    val hhv2 = hhvD(dx, 2); val hhv7 = hhvD(dx, 7)
    val llv2 = llvD(dx, 2); val llv7 = llvD(dx, 7)
    val cntHi = countC(dx.map { it > 50.0 }, 2)
    val cntLo = countC(dx.map { it < 0.0 }, 2)
    val sellRaw = closes.indices.map { i -> hhv2[i] == hhv7[i] && cntHi[i] > 0 && crossD(maDx2, dx)[i] }
    val buyRaw = closes.indices.map { i -> llv2[i] == llv7[i] && cntLo[i] > 0 && crossD(dx, maDx2)[i] }
    val sell = filterC(sellRaw, 1)
    val buy = filterC(buyRaw, 5)
    // ---- 控盘 (EMA(EMA(C,13),13) 动量×1000) ----
    val vaw1 = emaD(emaD(closes, 13), 13)
    val refVaw1 = refD(vaw1, 1)
    val kp = closes.indices.map { i ->
        val p = refVaw1[i]
        if (p.isNaN() || p == 0.0) Double.NaN else (vaw1[i] - p) / p * 1000.0
    }
    val refKp = refD(kp, 1)
    // ---- JJ 当日均价近似 + 偏离 ----
    val jj = bars.map { (it.open + it.high + it.low + it.close) / 4.0 }
    val refJj = refD(jj, 1)
    val jjRise = jj.indices.map { i -> !refJj[i].isNaN() && jj[i] >= refJj[i] }
    val dev = closes.indices.map { i -> if (jj[i] != 0.0) (closes[i] - jj[i]) / jj[i] else Double.NaN }
    // ---- 波段 财/神 ----
    val emaC8 = emaD(closes, 8)
    val emaC21 = emaD(closes, 21)
    val cai = closes.indices.map { i -> (emaC8[i] - emaC21[i]) * 50.0 }
    val shen = emaD(cai, 3)
    val crossCS = crossD(cai, shen)
    val crossSC = crossD(shen, cai)
    fun andAll(vararg cs: List<Boolean>) = closes.indices.map { i -> cs.all { it[i] } }
    // ---- 信号条件 ----
    val btj1 = dev.map { it < -0.03 }
    val btj12 = lastC(jjRise, 5, 1).mapIndexed { i, ok -> ok && dev[i] < 0.005 }
    val stj1 = dev.map { it > 0.005 }
    val stj01 = closes.indices.map { i -> !refKp[i].isNaN() && kp[i] < refKp[i] && kp[i] > 0.5 }
    val stj02 = closes.indices.map { i -> !kp[i].isNaN() && kp[i] > 0 }
    val btj2 = closes.indices.map { i -> !refKp[i].isNaN() && kp[i] > refKp[i] && kp[i] < -0.2 }
    val btj22 = closes.indices.map { i -> !refKp[i].isNaN() && kp[i] > refKp[i] && kp[i] < 0.0 }
    val btj3 = closes.indices.map { i -> crossCS[i] && cai[i] < -0.3 }
    val btj32 = closes.indices.map { i -> crossCS[i] && cai[i] < -0.1 }
    val stj31 = crossSC
    val stj32 = closes.indices.map { i -> crossSC[i] && cai[i] > 1.618 }
    val stj81 = filterC(andAll(stj1, stj01, stj31), 13)
    val stj82 = filterC(andAll(stj02, stj32), 13)
    val zhuangChu = closes.indices.map { i -> stj81[i] && stj82[i] }
    val zhuangJin = filterC(andAll(btj1, btj2, btj3), 30)
    // ---- 大跌日 ----
    val bigDrop = closes.indices.map { i -> i >= 1 && closes[i] < 0.905 * closes[i - 1] }
    return QipaoIndicators(qipao, attack, slow, conf, strong, sell, buy, zhuangJin, zhuangChu, bigDrop)
}

/** 按显示窗口截取指标序列。 */
private fun QipaoIndicators.window(from: Int, to: Int): QipaoIndicators = QipaoIndicators(
    qipao.subList(from, to), attack.subList(from, to), slow.subList(from, to), conf.subList(from, to),
    strong.subList(from, to), sell.subList(from, to), buy.subList(from, to),
    zhuangJin.subList(from, to), zhuangChu.subList(from, to), bigDrop.subList(from, to)
)

/**
 * 日K线蜡烛图 + 吞金起爆主图指标 + 成交量副图 + 十字光标交互。
 *
 * 指标 (通达信公式移植):
 * - 吞金起爆: MA(C,20)+MA(C,20)*18/1000.35  (MA20 上方约1.8%, 红)
 * - 主力攻击线 X_29 = (MA3+MA6+MA9)/3 (深灰, CONF成立段红色加粗)
 * - 吞金杀牛线 X_30 = (MA5+MA10+MA15+MA20+MA25+MA30)/6 (金黄, 粗)
 * - CONF := X_29>REF(X_29,1) AND X_29>X_30; CROSS(CONF,REF(CONF,1)) 首日画起爆红三角
 *
 * 交互:
 * - 手指按住/拖动: 显示十字光标与该日 OHLC 数据气泡
 * - 松手后光标保留, 点击气泡外区域可再次定位
 * - 周期切换: 60 / 120 / 250 日
 * - 万龙信号日: signalDates 命中的蜡烛上方标 🐉 并画橙色竖线, 气泡同步提示
 */
@Composable
fun KLineChart(
    bars: List<DailyBar>,
    modifier: Modifier = Modifier.fillMaxWidth().height(300.dp),
    showDays: Int = 60,
    signalDates: Set<String> = emptySet()
) {
    var selectedPeriod by remember { mutableStateOf(showDays) }
    var crossIndex by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("日K线走势(前复权)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Blue700)
                // 周期切换
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(60, 120, 250).forEach { d ->
                        FilterChip(
                            selected = selectedPeriod == d,
                            onClick = {
                                selectedPeriod = d
                                crossIndex = null
                            },
                            label = { Text("${d}日", fontSize = 10.sp) },
                            modifier = Modifier.height(26.dp).padding(end = 2.dp)
                        )
                    }
                }
            }

            if (bars.size < 5) {
                Text("数据不足", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 20.dp))
            } else {
                // ===== 吞金起爆全套主图指标 (通达信公式移植) =====
                val ind = computeQipao(bars)
                val total = bars.size
                val start = (total - selectedPeriod).coerceAtLeast(0)
                val slice = bars.subList(start, total)
                val localCross = crossIndex?.takeIf { it in slice.indices }

                // 指标图例(显示区间最后一根有效值) + 万龙信号图例
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "起爆 ${ind.qipao.lastOrNull()?.let { String.format("%.2f", it) } ?: "-"}",
                        fontSize = 9.sp, color = QipaoColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 9.dp)
                    )
                    Text(
                        "攻击 ${ind.attack.lastOrNull()?.let { String.format("%.2f", it) } ?: "-"}",
                        fontSize = 9.sp, color = AttackColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 9.dp)
                    )
                    Text(
                        "杀牛 ${ind.slow.lastOrNull()?.let { String.format("%.2f", it) } ?: "-"}",
                        fontSize = 9.sp, color = SlowColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 9.dp)
                    )
                    Text(
                        "走强 ${String.format("%.2f", ind.strong.lastOrNull() ?: Double.NaN)}",
                        fontSize = 9.sp, color = StrongColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 9.dp)
                    )
                    if (signalDates.isNotEmpty()) {
                        Text(
                            "🐉 万龙信号 ×${signalDates.size}",
                            fontSize = 9.sp, color = Color(0xFFEA580C),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    CandleStickChart(
                        bars = slice,
                        ind = ind.window(start, total),
                        crossIndex = localCross,
                        onCross = { crossIndex = it },
                        signalDates = signalDates,
                        modifier = Modifier.fillMaxWidth().weight(2.5f)
                    )
                    KLineVolumeChart(
                        bars = slice,
                        crossIndex = localCross,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
    }
}

/** 十字光标手势: 拖动/按住时回调索引。 */
private fun Modifier.chartCrossGesture(n: Int, onIndex: (Int) -> Unit): Modifier =
    this.pointerInput(n) {
        detectDragGestures(
            onDragStart = { off ->
                val i = (off.x.coerceAtLeast(0f) / size.width * n).toInt().coerceIn(0, n - 1)
                onIndex(i)
            },
            onDrag = { change, _ ->
                change.consume()
                val i = (change.position.x.coerceAtLeast(0f) / size.width * n).toInt().coerceIn(0, n - 1)
                onIndex(i)
            }
        )
    }.pointerInput(n) {
        detectTapGestures { off ->
            val i = (off.x.coerceAtLeast(0f) / size.width * n).toInt().coerceIn(0, n - 1)
            onIndex(i)
        }
    }

/** 蜡烛图区域 + 吞金起爆全套主图指标 + 十字光标数据气泡 + 万龙信号日标记。 */
@Composable
private fun CandleStickChart(
    bars: List<DailyBar>,
    ind: QipaoIndicators,
    crossIndex: Int?,
    onCross: (Int?) -> Unit,
    signalDates: Set<String>,
    modifier: Modifier
) {
    val qipao = ind.qipao
    val attack = ind.attack
    val slow = ind.slow
    val conf = ind.conf
    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFAFAFA))
                .chartCrossGesture(bars.size) { onCross(it) }
        ) {
            val n = bars.size
            val w = size.width
            val h = size.height
            val padLeft = 44.dp.toPx()
            val padRight = 8.dp.toPx()
            val padTop = 6.dp.toPx()
            val padBottom = 16.dp.toPx()
            val chartW = w - padLeft - padRight
            val chartH = h - padTop - padBottom

            // Y 轴范围: 价格区间 + 指标线极值 (含走强线)
            val strongVals = ind.strong.filter { !it.isNaN() }
            val maMin = minOf(
                qipao.filterNotNull().minOrNull() ?: Double.MAX_VALUE,
                attack.filterNotNull().minOrNull() ?: Double.MAX_VALUE,
                slow.filterNotNull().minOrNull() ?: Double.MAX_VALUE,
                strongVals.minOrNull() ?: Double.MAX_VALUE
            )
            val maMax = maxOf(
                qipao.filterNotNull().maxOrNull() ?: 0.0,
                attack.filterNotNull().maxOrNull() ?: 0.0,
                slow.filterNotNull().maxOrNull() ?: 0.0,
                strongVals.maxOrNull() ?: 0.0
            )
            val minL = minOf(bars.minOf { it.low }, maMin).coerceAtLeast(0.01)
            val maxH = maxOf(bars.maxOf { it.high }, maMax)
            if (maxH <= minL) return@Canvas

            fun xTo(i: Int): Float = padLeft + chartW * ((i + 0.5f) / n)
            fun yTo(p: Double): Float = padTop + chartH * (1f - ((p - minL) / (maxH - minL)).toFloat())

            // 网格 + Y 轴价格刻度(4分位)
            for (g in 0..3) {
                val frac = g.toFloat() / 3
                val y = padTop + chartH * (1 - frac)
                val valAt = minL + (maxH - minL) * frac
                drawLine(Color(0xFFE2E8F0), Offset(padLeft, y), Offset(w - padRight, y), 0.6f)
                drawContext.canvas.nativeCanvas.drawText(
                    String.format("%.2f", valAt), padLeft - 4.dp.toPx(), y + 3.dp.toPx(),
                    Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.RIGHT }
                )
            }

            // 蜡烛
            val candleW = chartW / n * 0.7f
            for (i in 0 until n) {
                val b = bars[i]
                val x = xTo(i)
                val yHigh = yTo(b.high)
                val yLow = yTo(b.low)
                val yOpen = yTo(b.open)
                val yClose = yTo(b.close)

                drawLine(Color(0xFF94A3B8), Offset(x, yHigh), Offset(x, yLow), 1f)
                val rectTop = minOf(yOpen, yClose)
                val rectH = kotlin.math.abs(yOpen - yClose).coerceAtLeast(1f)
                val fillColor = if (b.close >= b.open) Rise else Fall
                drawLine(fillColor, Offset(x, rectTop), Offset(x, rectTop + rectH), candleW)
            }

            // ===== 吞金起爆主图指标线 =====
            fun drawIndicatorLine(series: List<Double?>, color: Color, widthDp: Float) {
                val path = Path()
                var started = false
                for (i in 0 until n) {
                    val v = series[i] ?: continue
                    val x = xTo(i); val y = yTo(v)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                if (started) drawPath(path, color, style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round))
            }
            drawIndicatorLine(qipao, QipaoColor, 2f)     // 吞金起爆线(红, 粗)
            drawIndicatorLine(slow, SlowColor, 2f)       // 吞金杀牛线(金黄, 粗)
            drawIndicatorLine(attack, AttackColor, 1.2f)  // 主力攻击线(深灰)

            // 攻击线 CONF 成立段: 红色加粗 (IF(CONF, X_29, DRAWNULL) 移植)
            run {
                val overlay = Path()
                var drawing = false
                for (i in 0 until n) {
                    if (conf[i] && attack[i] != null) {
                        val x = xTo(i); val y = yTo(attack[i]!!)
                        if (drawing) overlay.lineTo(x, y) else { overlay.moveTo(x, y); drawing = true }
                    } else {
                        drawing = false
                    }
                }
                drawPath(overlay, QipaoColor, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
            }

            // 起爆信号: CROSS(CONF,REF(CONF,1)) 首日, 最低价下方画红色上三角 (DRAWICON 移植)
            run {
                val icon = Path()
                for (i in 1 until n) {
                    if (conf[i] && !conf[i - 1]) {
                        val x = xTo(i)
                        val y = yTo(bars[i].low) + 9.dp.toPx()
                        icon.moveTo(x - 4.5f.dp.toPx(), y)
                        icon.lineTo(x + 4.5f.dp.toPx(), y)
                        icon.lineTo(x, y - 7.dp.toPx())
                        icon.close()
                    }
                }
                drawPath(icon, QipaoColor)
            }

            // X 轴日期(首中尾)
            if (n >= 3) {
                for (i in intArrayOf(0, n / 2, n - 1)) {
                    drawContext.canvas.nativeCanvas.drawText(
                        bars[i].date.takeLast(5), xTo(i), h - 2.dp.toPx(),
                        Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.CENTER }
                    )
                }
            }

            // ===== 万龙信号日标记: 橙色竖线 + 蜡烛上方 🐉 =====
            if (signalDates.isNotEmpty()) {
                val dragonPaint = Paint().apply { textSize = 10.sp.toPx(); textAlign = Paint.Align.CENTER }
                for (i in 0 until n) {
                    if (bars[i].date !in signalDates) continue
                    val x = xTo(i)
                    // 淡橙竖线贯穿图区, 一眼定位信号日
                    drawLine(Color(0x66EA580C), Offset(x, padTop), Offset(x, h - padBottom), 1.dp.toPx())
                    // 🐉 标记在蜡烛最高点上方(避免越界裁剪)
                    val yMark = (yTo(bars[i].high) - 5.dp.toPx()).coerceAtLeast(padTop + 10.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawText("🐉", x, yMark, dragonPaint)
                }
            }

            // ===== 走强线: EMA(COST(99)近似, 181) (亮琥珀粗虚线, 与杀牛线区分) =====
            run {
                val path = Path()
                var started = false
                for (i in 0 until n) {
                    val v = ind.strong.getOrNull(i) ?: continue
                    if (v.isNaN()) continue
                    val x = xTo(i); val y = yTo(v)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                if (started) drawPath(
                    path, StrongColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                        cap = StrokeCap.Round
                    )
                )
            }

            // ===== 大跌K线: C<0.905*REF(C,1) 收盘到开盘画深绿粗棒 (STICKLINE 移植) =====
            for (i in 0 until n) {
                if (!ind.bigDrop[i]) continue
                val x = xTo(i)
                val yO = yTo(bars[i].open)
                val yC = yTo(bars[i].close)
                drawLine(Color(0xFF15803D), Offset(x, minOf(yO, yC)), Offset(x, maxOf(yO, yC)), candleW * 1.2f)
            }

            // ===== 卖 / 买★ / ↖庄进 / ↓庄出 文本标记 (DRAWTEXT 移植, 图标23/24由文字承载) =====
            run {
                val tp = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
                for (i in 0 until n) {
                    val x = xTo(i)
                    val b = bars[i]
                    if (ind.sell[i]) {
                        tp.color = 0xFF1E293B.toInt(); tp.textSize = 10.sp.toPx()
                        val y = yTo(b.high * 1.02).coerceAtLeast(padTop + 12.dp.toPx())
                        drawContext.canvas.nativeCanvas.drawText("卖", x, y, tp)
                    }
                    if (ind.buy[i]) {
                        tp.color = 0xFFDC2626.toInt(); tp.textSize = 12.sp.toPx()
                        val y = yTo(b.low * 0.97).coerceAtMost(h - padBottom - 4.dp.toPx())
                        drawContext.canvas.nativeCanvas.drawText("★", x, y, tp)
                    }
                    if (ind.zhuangJin[i]) {
                        tp.color = 0xFFB45309.toInt(); tp.textSize = 9.sp.toPx()
                        val y = yTo(b.low * 0.995).coerceAtMost(h - padBottom - 4.dp.toPx())
                        drawContext.canvas.nativeCanvas.drawText("庄进", x, y, tp)
                    }
                    if (ind.zhuangChu[i]) {
                        tp.color = 0xFF16A34A.toInt(); tp.textSize = 9.sp.toPx()
                        val y = yTo(b.high * 1.005).coerceAtLeast(padTop + 12.dp.toPx())
                        drawContext.canvas.nativeCanvas.drawText("庄出", x, y, tp)
                    }
                }
            }

            // ===== 十字光标 =====
            if (crossIndex != null && crossIndex in 0 until n) {
                val cx = xTo(crossIndex)
                val b = bars[crossIndex]
                drawLine(Color(0xFF475569), Offset(cx, padTop), Offset(cx, h - padBottom), 1f)
                val cy = yTo(b.close)
                drawLine(Color(0xFF475569), Offset(padLeft, cy), Offset(w - padRight, cy), 1f)
            }
        }

        // 数据气泡(十字光标 OHLC 悬浮卡)
        if (crossIndex != null && crossIndex in bars.indices) {
            val b = bars[crossIndex]
            val bubbleAtLeft = crossIndex > bars.size / 2  // 光标在右半边, 气泡放左侧避让
            Surface(
                color = Color(0xCC1E293B),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(if (bubbleAtLeft) Alignment.TopStart else Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(b.date, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    if (b.date in signalDates) {
                        Text("🐉 万龙信号日", fontSize = 9.sp, color = Color(0xFFFDBA74), fontWeight = FontWeight.Bold)
                    }
                    Text("开 ${String.format("%.2f", b.open)}  高 ${String.format("%.2f", b.high)}", fontSize = 9.sp, color = Color(0xFFE2E8F0))
                    Text("低 ${String.format("%.2f", b.low)}  收 ${String.format("%.2f", b.close)}", fontSize = 9.sp, color = Color(0xFFE2E8F0))
                    val pct = if (crossIndex > 0) (b.close / bars[crossIndex - 1].close - 1) * 100 else 0.0
                    Text(
                        "涨跌 ${String.format("%+.2f%%", pct)}  量 ${String.format("%.0f万", b.volume / 10000)}",
                        fontSize = 9.sp,
                        color = if (pct >= 0) Color(0xFFFCA5A5) else Color(0xFF86EFAC)
                    )
                }
            }
        }
    }
}

/** K线成交量副图(红涨绿跌, 十字光标同步)。 */
@Composable
private fun KLineVolumeChart(
    bars: List<DailyBar>,
    crossIndex: Int?,
    modifier: Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFAFAFA))
    ) {
        val n = bars.size
        val w = size.width
        val h = size.height
        val padLeft = 44.dp.toPx()
        val padRight = 8.dp.toPx()
        val padBottom = 10.dp.toPx()
        val chartW = w - padLeft - padRight
        val chartH = h - padBottom

        val maxVol = bars.maxOf { it.volume }.coerceAtLeast(1.0)
        fun xTo(i: Int): Float = padLeft + chartW * ((i + 0.5f) / n)

        val barW = chartW / n * 0.6f
        for (i in 0 until n) {
            val b = bars[i]
            val x = xTo(i)
            val barH = (b.volume / maxVol * chartH).toFloat()
            val color = if (b.close >= b.open) Rise else Fall
            drawLine(color, Offset(x, h - 2.dp.toPx()), Offset(x, h - 2.dp.toPx() - barH), barW)
        }

        // 成交量最大值
        drawContext.canvas.nativeCanvas.drawText(
            String.format("%.0f万", maxVol / 10000), padLeft, 10.dp.toPx(),
            Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.LEFT }
        )

        // 十字光标垂直线同步
        if (crossIndex != null && crossIndex in 0 until n) {
            val cx = xTo(crossIndex)
            drawLine(Color(0xFF475569), Offset(cx, 0f), Offset(cx, h - padBottom), 1f)
        }
    }
}
