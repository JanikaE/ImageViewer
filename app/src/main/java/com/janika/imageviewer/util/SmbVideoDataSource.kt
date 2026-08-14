package com.janika.imageviewer.util

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbFile
import com.janika.imageviewer.data.repository.SmbSessionManager

/**
 * SMB 视频流式数据源 - 基于 SMBJ 随机读实现 Media3 DataSource。
 *
 * 打开一次 [SmbFile] 句柄，按 offset 顺序读取，天然支持拖动 seek（Media3 会以新的
 * position 重新 [open]，这里把 dataSpec.position 当作起始读取偏移即可）。
 * 共享会话由 [SmbSessionManager] 统一持有，播放期间保持连接。
 */
@UnstableApi
class SmbVideoDataSource(
    private val shareName: String,
    private val filePath: String,
    private val onBytesRead: (Long) -> Unit = {}
) : BaseDataSource(/* isNetwork = */ true) {

    private var smbFile: SmbFile? = null
    private var uri: Uri? = null
    private var readOffset = 0L
    private var contentLength = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferStarted(dataSpec)
        uri = dataSpec.uri
        val file = SmbSessionManager.getDiskShare(shareName).openFile(
            filePath,
            setOf(AccessMask.GENERIC_READ),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        )
        smbFile = file
        contentLength = file.getFileInformation(FileStandardInformation::class.java).endOfFile
        readOffset = dataSpec.position
        return (contentLength - readOffset).coerceAtLeast(0L)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val file = smbFile ?: return C.RESULT_END_OF_INPUT
        val read = try {
            file.read(buffer, readOffset, offset, length)
        } catch (e: Exception) {
            // 会话断开等异常原样抛出，让 Media3 按播放错误处理（可重试）
            throw e
        }
        if (read <= 0) return C.RESULT_END_OF_INPUT
        readOffset += read
        bytesTransferred(read)
        onBytesRead(read.toLong())
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            smbFile?.close()
        } catch (_: Exception) {
        }
        smbFile = null
        transferEnded()
    }
}
