package com.example.momentum.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 同一应用创建的同名文件更新原条目，重试不产生多份下载索引。 */
object PublicDownloadWriter {
    fun write(context: Context, subdir: String, name: String, bytes: ByteArray, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val path = "${Environment.DIRECTORY_DOWNLOADS}/MomentumRotation/$subdir/"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val existing = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(name, path), null)?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = existing ?: requireNotNull(resolver.insert(collection, values)) { "无法创建下载文件" }
            resolver.update(uri, values, null, null)
            requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入下载文件" }.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MomentumRotation/$subdir")
            // 旧 Android 无公共目录权限时，应用内部副本仍可通过分享访问。
            runCatching { dir.mkdirs(); File(dir, name).writeBytes(bytes) }
        }
    }
}
