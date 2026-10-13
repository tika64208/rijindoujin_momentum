package com.example.momentum

import android.app.Application
import com.example.momentum.util.CrashLogManager

class MomentumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 全局未捕获异常捕获与错误日志管理初始化
        CrashLogManager.install(this)
    }
}
