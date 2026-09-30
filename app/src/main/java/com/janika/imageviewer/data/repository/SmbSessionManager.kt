package com.janika.imageviewer.data.repository

import android.util.Log
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 按服务器地址隔离的 SMBJ 会话池。
 *
 * 每台服务器独立持有 SMBClient、Connection、Session 与共享缓存；连接或关闭某台服务器
 * 不会影响其他服务器。所有文件读取都必须同时提供服务器地址和共享名。
 */
object SmbSessionManager {
    private class SessionHolder {
        val lock = Any()
        var credentialKey: String = ""
        var client: SMBClient? = null
        var connection: Connection? = null
        var session: Session? = null
        val shares = ConcurrentHashMap<String, DiskShare>()
    }

    private val holders = ConcurrentHashMap<String, SessionHolder>()

    fun isConnected(serverAddress: String): Boolean {
        val holder = holders[serverAddress.trim()] ?: return false
        val connection = holder.connection
        return connection != null && connection.isConnected && holder.session != null
    }

    suspend fun connect(
        serverAddress: String,
        username: String?,
        password: String?,
        domain: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val address = serverAddress.trim()
        if (address.isEmpty()) return@withContext false
        val key = listOf(username.orEmpty(), password.orEmpty(), domain.orEmpty()).joinToString("\u0000")
        val holder = holders.computeIfAbsent(address) { SessionHolder() }
        synchronized(holder.lock) {
            if (holder.credentialKey == key && holder.connection?.isConnected == true &&
                holder.session != null
            ) {
                return@synchronized true
            }
            closeQuietly(holder)
            try {
                val client = SMBClient(buildConfig())
                holder.client = client
                val connection = client.connect(address)
                holder.connection = connection
                val auth = if (!username.isNullOrEmpty()) {
                    AuthenticationContext(username, password.orEmpty().toCharArray(), domain.orEmpty())
                } else {
                    AuthenticationContext.anonymous()
                }
                val session = connection.authenticate(auth)
                holder.session = session
                holder.credentialKey = key
                val protocol = connection.getNegotiatedProtocol()
                Log.i(
                    "SmbSessionManager",
                    "连接成功: $address, dialect=${protocol.dialect}, " +
                        "maxRead=${protocol.maxReadSize}, maxWrite=${protocol.maxWriteSize}, " +
                        "maxTransact=${protocol.maxTransactSize}"
                )
                true
            } catch (e: Exception) {
                if (e is CancellationException) {
                    closeQuietly(holder)
                    throw e
                }
                Log.e("SmbSessionManager", "连接失败: $address", e)
                closeQuietly(holder)
                false
            }
        }
    }

    fun getDiskShare(serverAddress: String, shareName: String): DiskShare {
        val address = serverAddress.trim()
        val holder = holders[address] ?: throw SMBRuntimeException("尚未连接 SMB 服务器: $address")
        synchronized(holder.lock) {
            holder.shares[shareName]?.let { if (it.isConnected) return it }
            val session = holder.session ?: throw SMBRuntimeException("尚未连接 SMB 服务器: $address")
            val share = session.connectShare(shareName)
            if (share !is DiskShare) {
                share.close()
                throw SMBRuntimeException("$shareName 不是磁盘共享")
            }
            holder.shares[shareName] = share
            return share
        }
    }

    fun disconnect(serverAddress: String) {
        val holder = holders.remove(serverAddress.trim()) ?: return
        synchronized(holder.lock) { closeQuietly(holder) }
    }

    fun disconnectAll() {
        holders.keys.toList().forEach(::disconnect)
    }

    fun testConnection(
        serverAddress: String,
        username: String?,
        password: String?,
        domain: String?,
        shareName: String? = null
    ): Boolean {
        var client: SMBClient? = null
        var connection: Connection? = null
        var session: Session? = null
        return try {
            client = SMBClient(buildConfig())
            connection = client.connect(serverAddress.trim())
            val auth = if (!username.isNullOrEmpty()) {
                AuthenticationContext(username, password.orEmpty().toCharArray(), domain.orEmpty())
            } else {
                AuthenticationContext.anonymous()
            }
            session = connection.authenticate(auth)
            if (!shareName.isNullOrBlank()) session.connectShare(shareName).close()
            true
        } catch (e: Exception) {
            Log.w("SmbSessionManager", "连接测试失败: $serverAddress", e)
            false
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { connection?.close() } catch (_: Exception) {}
            try { client?.close() } catch (_: Exception) {}
        }
    }

    private fun closeQuietly(holder: SessionHolder) {
        holder.shares.values.forEach { try { it.close() } catch (_: Exception) {} }
        holder.shares.clear()
        try { holder.session?.close() } catch (_: Exception) {}
        try { holder.connection?.close() } catch (_: Exception) {}
        try { holder.client?.close() } catch (_: Exception) {}
        holder.session = null
        holder.connection = null
        holder.client = null
        holder.credentialKey = ""
    }

    private fun buildConfig(): SmbConfig = SmbConfig.builder()
        .withTimeout(30, TimeUnit.SECONDS)
        .build()
}
