package com.example.momentum

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.momentum.ui.screen.MomentumScreen
import com.example.momentum.ui.screen.MomentumViewModel
import com.example.momentum.ui.screen.SettingsScreen
import com.example.momentum.ui.theme.SurfaceBg

class MainActivity : ComponentActivity() {

    // Android 13+ 通知权限请求
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 请求通知权限(Android 13+), 低版本无需
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SurfaceBg
                ) {
                    val viewModel: MomentumViewModel = viewModel()
                    // 首次启动注册一次提醒任务(读已有设置)
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        viewModel.rescheduleReminder()
                    }
                    var showSettings by remember { mutableStateOf(false) }

                    if (showSettings) {
                        SettingsScreen(
                            onBack = { showSettings = false },
                            viewModel = viewModel
                        )
                    } else {
                        MomentumScreen(
                            viewModel = viewModel,
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }
}

