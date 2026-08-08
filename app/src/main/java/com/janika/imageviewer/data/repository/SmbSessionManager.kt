package com.janika.imageviewer.data.repository

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.common.SMBRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SMB 会话管理器 - 持有 SMBJ 的连接/会话/共享，供浏览与下载共用。
 *
 * 认证只在 [connect] 时进行一次，之后通过 [getDiskShare] 复用已认证的共享，
 * 不再需要 jcifs-ng 那套"共享上下文 + 逐文件认证回退"。
 */
object SmbSessionManager {

    private val lock = Any()

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null

    private val shares = ConcurrentHashMap<String, DiskShare>()

    /** 是否已建立连接 */
    fun isConnected(): Boolean {
        val conn = connection
        return conn != null && conn.isConnected && session != null
    }

    /**
     * 建立连接并认证。共享名不在连接时校验，浏览时才按需 connectShare。
     * 阻塞网络操作，必须在 IO 线程执行。
     */
    suspend fun connect(
        serverAddress: String,
        username: String?,
        password: String?,
        domain: String?
    ): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            disconnect()
            try {
                val c = SMBClient(buildConfig())
                val conn = c.connect(serverAddress)
                val auth = if (!username.isNullOrEmpty()) {
                    AuthenticationContext(username, (password ?: "").toCharArray(), domain ?: "")
                } else {
                    AuthenticationContext.anonymous()
                }
                val s = conn.authenticate(auth)
                client = c
                connection = conn
                session = s
                shares.clear()
                // 打印协商结果，便于判断服务器实际读块上限与是否启用大读块
                val proto = conn.getNegotiatedProtocol()
                android.util.Log.i(
                    "SmbSessionManager",
                    "连接成功: $serverAddress, dialect=${proto.dialect}, " +
                        "maxRead=${proto.maxReadSize}, maxWrite=${proto.maxWriteSize}, " +
                        "maxTransact=${proto.maxTransactSize}"
                )
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("SmbSessionManager", "连接失败: $serverAddress", e)
                closeQuietly()
                false
            }
        }
    }

    /**
     * 获取（并按需建立）指定共享的 DiskShare。下载与浏览共用。
     */
    fun getDiskShare(shareName: String): DiskShare {
        val cur = session ?: throw SMBRuntimeException("尚未连接 SMB 服务器")
        synchronized(lock) {
            shares[shareName]?.let { if (it.isConnected) return it }
            val share = cur.connectShare(shareName)
            if (share !is DiskShare) {
                share.close()
                throw SMBRuntimeException("$shareName 不是磁盘共享")
            }
            shares[shareName] = share
            return share
        }
    }

    /** 断开连接并释放全部资源 */
    fun disconnect() {
        synchronized(lock) {
            closeQuietly()
        }
    }

    private fun closeQuietly() {
        try {
            shares.values.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        shares.clear()
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        try {
            client?.close()
        } catch (_: Exception) {
        }
        session = null
        connection = null
        client = null
    }

    /**
     * 设置页的连接测试：验证服务器 + 凭据，可选校验一个共享名。
     */
    fun testConnection(
        serverAddress: String,
        username: String?,
        password: String?,
        domain: String?,
        shareName: String? = null
    ): Boolean {
        var conn: Connection? = null
        return try {
            val c = SMBClient(buildConfig())
            val connection = c.connect(serverAddress)
            conn = connection
            val auth = if (!username.isNullOrEmpty()) {
                AuthenticationContext(username, (password ?: "").toCharArray(), domain ?: "")
            } else {
                AuthenticationContext.anonymous()
            }
            val session = connection.authenticate(auth)
            if (!shareName.isNullOrBlank()) {
                session.connectShare(shareName).close()
            }
            session.close()
            connection.close()
            true
        } catch (e: Exception) {
            android.util.Log.w("SmbSessionManager", "连接测试失败: $serverAddress", e)
            try {
                conn?.close()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun buildConfig(): SmbConfig =
        SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .build()
}
