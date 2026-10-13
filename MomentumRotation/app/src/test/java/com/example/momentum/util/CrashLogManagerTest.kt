package com.example.momentum.util

import org.junit.Assert.*
import org.junit.Test

class CrashLogManagerTest {

    @Test
    fun testCrashLogEntryModel() {
        val entry = CrashLogEntry(
            id = "test-123",
            timestamp = 1791876546000L,
            timeFormatted = "2026-10-13 15:29:06",
            type = "CRASH",
            tag = "StockDetailScreen",
            message = "HTTP 501",
            stackTrace = "java.lang.RuntimeException: HTTP 501",
            deviceInfo = "Xiaomi 15"
        )

        assertEquals("test-123", entry.id)
        assertEquals("CRASH", entry.type)
        assertEquals("HTTP 501", entry.message)
        assertEquals("StockDetailScreen", entry.tag)
        assertEquals("Xiaomi 15", entry.deviceInfo)
    }

    @Test
    fun testDeviceInfoSafe() {
        val info = CrashLogManager.getDeviceInfo()
        assertNotNull(info)
        assertTrue(info.contains("Android 版本") && info.contains("设备:"))
    }
}
