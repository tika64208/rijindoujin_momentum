package com.example.momentum

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.example.momentum.data.SimTradingDataRepository
import com.example.momentum.data.SimTradingManager
import com.example.momentum.engine.BidStrategy
import com.example.momentum.engine.SimTradingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 在已授权的真实设备上验证独立报告；不买入、不结算、不重置账户。 */
class SimAuditInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val output = Bundle()
        try {
            runBlocking(Dispatchers.IO) {
                val accountFile = File(targetContext.filesDir, "sim_trading_account.json")
                val before = accountFile.readBytes()
                val manager = SimTradingManager.get(targetContext)
                val account = manager.accountState.value
                val report = manager.runBacktest(BidStrategy.BidConfig.HIGH_WIN_RATE, true)
                check(before.contentEquals(accountFile.readBytes())) { "回测改变了账户文件" }
                check(account == manager.accountState.value) { "回测改变了内存账户" }
                val today = account.todayRecord
                if (today != null && today.orders.isNotEmpty()) {
                    val day = SimTradingDataRepository(targetContext).loadDay(today.date)
                    val replay = SimTradingEngine.simulateDay(today.startCapital, today.date, day.rows,
                        BidStrategy.BidConfig.HIGH_WIN_RATE, today.executionFeeConfig ?: account.feeConfig)
                    output.putString("todayReplay", replay.toJson().toString())
                    output.putString("todayLedger", today.toJson().toString())
                }
                output.putString("accountSha256", MessageDigest.getInstance("SHA-256").digest(before).joinToString("") { "%02x".format(it) })
                output.putString("report", JSONObject().put("historicalReturnPct", report.result.totalReturnPct)
                    .put("days", report.result.historyRecords.size).put("unverifiedDays", report.notes.size)
                    .put("skipped", JSONObject(report.skipped)).toString())
                output.putString("result", "account_unchanged_report_saved")
            }
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            output.putString("error", e.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
