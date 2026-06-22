package com.janika.imageviewer.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * 将图片保存到系统相册
 */
object MediaSaver {

    /**
     * 保存图片文件到相册，返回是否成功
     */
    fun saveToGallery(context: Context, sourceFile: File, displayName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveUsingMediaStore(context, sourceFile, displayName)
            } else {
                saveToExternalStorage(context, sourceFile, displayName)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaSaver", "保存图片失败: ${sourceFile.absolutePath}", e)
            false
        }
    }

    private fun saveUsingMediaStore(context: Context, sourceFile: File, displayName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, getMimeType(displayName))
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ImageViewer")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false

        context.contentResolver.openOutputStream(uri)?.use { output ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(output)
            }
        } ?: return false
        return true
    }

    private fun saveToExternalStorage(context: Context, sourceFile: File, displayName: String): Boolean {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ImageViewer")
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, displayName)
        sourceFile.copyTo(dest, overwrite = true)
        // 通知相册扫描
        MediaStore.Images.Media.insertImage(
            context.contentResolver, dest.absolutePath, dest.name, null
        )
        return true
    }

    private fun getMimeType(fileName: String): String = when {
        fileName.endsWith(".png", ignoreCase = true) -> "image/png"
        fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
        fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg"
    }
}
