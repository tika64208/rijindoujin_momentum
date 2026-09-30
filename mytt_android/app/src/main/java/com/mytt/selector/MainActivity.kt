package com.mytt.selector

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * MainActivity —— 单只股票 MYTT 信号查询界面
 * 输入 6 位代码 -> 点“查询信号” -> 后台拉数据并计算 -> 显示结果。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etCode: EditText
    private lateinit var btnGo: Button
    private lateinit var tvResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etCode = findViewById(R.id.et_code)
        btnGo = findViewById(R.id.btn_go)
        tvResult = findViewById(R.id.tv_result)

        btnGo.setOnClickListener { runQuery() }
    }

    private fun runQuery() {
        val code = etCode.text.toString().trim()
        if (code.length != 6 || !code.all { it.isDigit() }) {
            Toast.makeText(this, "请输入6位股票代码", Toast.LENGTH_SHORT).show()
            return
        }
        tvResult.text = "正在获取 ${code} 日线数据..."
        btnGo.isEnabled = false
        thread {
            val resultText: String = try {
                val kline = SinaClient.fetchDaily(code)
                val r = Mytt.compute(kline, code)
                buildReport(code, r)
            } catch (e: Exception) {
                "获取失败: ${e.message ?: e.javaClass.simpleName}\n" +
                        "请检查网络, 或确认代码是否正确(如 600519)。"
            }
            runOnUiThread {
                btnGo.isEnabled = true
                tvResult.text = resultText
            }
        }
    }

    private fun buildReport(code: String, r: Mytt.Result): String {
        val sb = StringBuilder()
        sb.append("股票代码: ").append(code).append("\n")
        sb.append("数据日期: ").append(r.date).append("\n")
        sb.append("收盘价: ").append("%.2f".format(r.close)).append("\n")
        sb.append("当日涨幅: ").append("%.2f%%".format(r.chgPct)).append("\n")
        sb.append("K线数: ").append(r.bars).append("\n")
        sb.append("--------------------------------\n")
        sb.append("万手单: ").append(if (r.sigWanshou) "✓ 命中" else "— 未命中").append("\n")
        sb.append("寻龙诀4号: ").append(if (r.sigXlj4) "✓ 命中" else "— 未命中").append("\n")
        sb.append("综合信号: ").append(
            when {
                r.signal -> "✓✓ 双信号命中(买入关注)"
                r.sigWanshou -> "万手单单边命中"
                r.sigXlj4 -> "寻龙诀4号单边命中"
                else -> "无信号"
            }
        ).append("\n")
        sb.append("--------------------------------\n")
        sb.append("距上次寻龙诀信号周期: ").append(if (r.xlj47.isNaN()) "-" else "%.0f".format(r.xlj47)).append("\n")
        sb.append("万手单形态条件(WSD_25): ").append(if (r.wsd25) "满足" else "不满足")
        return sb.toString()
    }
}
