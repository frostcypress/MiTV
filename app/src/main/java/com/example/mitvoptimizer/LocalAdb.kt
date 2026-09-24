package com.example.mitvoptimizer

import android.content.Context
import android.util.Base64
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Direct ADB client to the TV's own adbd on 127.0.0.1:5555.
 *
 * MiTV4A real-device testing showed an older/custom adbd CNXN string without a
 * `features=` field. The previous dadb client rejected that connection before a
 * shell could be opened. This implementation deliberately uses the legacy ADB
 * `shell:` service and does not require feature negotiation.
 */
class LocalAdb(private val context: Context) : AutoCloseable {
    data class Result(val ok: Boolean, val output: String, val exitCode: Int)

    private var socket: Socket? = null
    private var connection: AdbConnection? = null

    @Synchronized
    @Throws(IOException::class)
    private fun ensureConnection(): AdbConnection {
        connection?.let { return it }

        val crypto = loadOrCreateCrypto()
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", 5555), SOCKET_CONNECT_TIMEOUT_MS)
            val c = AdbConnection.create(s, crypto)
            val connected = try {
                c.connect(ADB_HANDSHAKE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS, false)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("ADB连接被中断", e)
            }
            if (!connected) {
                try { c.close() } catch (_: Throwable) {}
                throw IOException("ADB握手超时。如电视弹出RSA授权，请选择允许后再测试一次。")
            }
            socket = s
            connection = c
            return c
        } catch (t: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            if (t is IOException) throw t
            throw IOException(t.message ?: t.javaClass.simpleName, t)
        }
    }

    @Throws(IOException::class)
    private fun loadOrCreateCrypto(): AdbCrypto {
        // Keep this separate from the old dadb key files because their on-disk
        // encoding is different. Upgrading to v0.7 can therefore trigger one new
        // RSA authorisation prompt, after which this key is reused.
        val keyDir = File(context.filesDir, "adbkeys_legacy")
        if (!keyDir.exists() && !keyDir.mkdirs()) {
            throw IOException("无法创建ADB密钥目录")
        }
        val privateKey = File(keyDir, "adbkey.pk8")
        val publicKey = File(keyDir, "adbkey.x509")
        val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }

        if (privateKey.isFile && publicKey.isFile) {
            try {
                return AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey)
            } catch (_: Throwable) {
                privateKey.delete()
                publicKey.delete()
            }
        }

        return try {
            AdbCrypto.generateAdbKeyPair(base64).also {
                it.saveAdbKeyPair(privateKey, publicKey)
            }
        } catch (t: Throwable) {
            throw IOException("生成ADB RSA密钥失败: ${t.message}", t)
        }
    }

    /** Execute through the old, widely supported `shell:` service. */
    @Throws(IOException::class)
    fun shell(command: String): Result {
        // Legacy shell does not provide a separate exit-code channel. Append a
        // unique marker and recover `$?` from the command output.
        val wrapped = "$command; echo ${EXIT_MARKER}\$?__"
        val raw = executeShellRaw(wrapped)
        val match = EXIT_REGEX.find(raw)
        if (match == null) {
            return Result(false, raw.trim(), -1)
        }
        val code = match.groupValues[1].toIntOrNull() ?: -1
        val output = raw.substring(0, match.range.first).trimEnd('\r', '\n')
        return Result(code == 0, output, code)
    }

    @Throws(IOException::class)
    fun ping(): Result = shell("echo MITV_LOCAL_ADB_OK")

    /**
     * Push with the original ADB `sync:` v1 service, then install with Package Manager.
     * `sync:` predates shell-v2/features negotiation and is suitable for older adbd.
     */
    @Throws(IOException::class)
    fun install(apk: File): Result {
        if (!apk.isFile || apk.length() <= 0) {
            return Result(false, "APK不存在或为空: ${apk.absolutePath}", -1)
        }

        val remote = "/data/local/tmp/mitv_${UUID.randomUUID().toString().replace("-", "")}.apk"
        return try {
            pushBySyncV1(apk, remote)
            val installRaw = shell("pm install -r ${shellQuote(remote)}")
            try { shell("rm -f ${shellQuote(remote)}") } catch (_: Throwable) {}
            val text = installRaw.output.trim()
            val success = installRaw.ok && text.lines().any { it.trim().equals("Success", ignoreCase = true) }
            Result(success, installRaw.output, installRaw.exitCode)
        } catch (t: Throwable) {
            try { shell("rm -f ${shellQuote(remote)}") } catch (_: Throwable) {}
            if (t is IOException) throw t
            throw IOException(t.message ?: t.javaClass.simpleName, t)
        }
    }

    /** Minimal ADB sync v1 SEND/DATA/DONE implementation. */
    @Throws(IOException::class)
    private fun pushBySyncV1(local: File, remote: String) {
        val c = ensureConnection()
        val stream = try {
            c.open("sync:")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("打开ADB sync通道被中断", e)
        }

        try {
            // 0100000 (regular file) | 0644 = 33188 decimal.
            val pathAndMode = "$remote,33188".toByteArray(Charsets.UTF_8)
            writeStream(stream, "SEND".toByteArray(Charsets.US_ASCII) + le32(pathAndMode.size))
            writeStream(stream, pathAndMode)

            val maxData = try { c.maxData } catch (_: Throwable) { 4096 }
            val chunkSize = if (maxData > 0) minOf(4096, maxData) else 4096
            val buffer = ByteArray(chunkSize)
            FileInputStream(local).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    writeStream(stream, "DATA".toByteArray(Charsets.US_ASCII) + le32(n))
                    writeStream(stream, if (n == buffer.size) buffer.copyOf() else buffer.copyOf(n))
                }
            }

            val mtimeSeconds = (local.lastModified() / 1000L).coerceIn(0L, 0xffffffffL).toLong()
            writeStream(stream, "DONE".toByteArray(Charsets.US_ASCII) + le32(mtimeSeconds.toInt()))

            val first = readStream(stream)
            if (first.size < 4) throw IOException("ADB sync返回数据过短")
            val id = String(first, 0, 4, Charsets.US_ASCII)
            if (id == "FAIL") {
                val all = ByteArrayOutputStream().apply { write(first) }
                while (all.size() < 8) all.write(readStream(stream))
                val bytes = all.toByteArray()
                val len = readLe32(bytes, 4)
                while (all.size() < 8 + len) all.write(readStream(stream))
                val full = all.toByteArray()
                val msg = if (len > 0) String(full, 8, minOf(len, full.size - 8), Charsets.UTF_8) else "未知错误"
                throw IOException("ADB sync上传失败: $msg")
            }
            if (id != "OKAY") throw IOException("ADB sync上传返回未知状态: $id")

            try { writeStream(stream, "QUIT".toByteArray(Charsets.US_ASCII) + le32(0)) } catch (_: Throwable) {}
        } finally {
            try { stream.close() } catch (_: Throwable) {}
        }
    }

    @Throws(IOException::class)
    private fun writeStream(stream: AdbStream, data: ByteArray) {
        try {
            stream.write(data)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("ADB文件传输被中断", e)
        }
    }

    @Throws(IOException::class)
    private fun readStream(stream: AdbStream): ByteArray {
        return try {
            stream.read()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("读取ADB sync响应被中断", e)
        }
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte()
    )

    private fun readLe32(data: ByteArray, offset: Int): Int {
        if (offset < 0 || data.size < offset + 4) return 0
        return (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
    }

    /** Send reboot without waiting for an exit marker; the transport is expected to disappear. */
    @Throws(IOException::class)
    fun reboot(): Result {
        val c = ensureConnection()
        return try {
            c.open("shell:reboot")
            try { Thread.sleep(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            Result(true, "已发送电视重启命令。", 0)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("发送重启命令被中断", e)
        }
    }

    @Throws(IOException::class)
    private fun executeShellRaw(command: String): String {
        val c = ensureConnection()
        val stream: AdbStream = try {
            c.open("shell:$command")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("打开ADB shell被中断", e)
        }

        val out = ByteArrayOutputStream()
        try {
            while (true) {
                try {
                    val data = stream.read()
                    out.write(data)
                    if (stream.isClosed) break
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("读取ADB shell输出被中断", e)
                } catch (e: IOException) {
                    if (stream.isClosed) break
                    throw e
                }
            }
        } finally {
            try { stream.close() } catch (_: Throwable) {}
        }
        return out.toString(Charsets.UTF_8.name())
    }

    override fun close() {
        try { connection?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        connection = null
        socket = null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val SOCKET_CONNECT_TIMEOUT_MS = 5000
        private const val ADB_HANDSHAKE_TIMEOUT_MS = 45000
        private const val EXIT_MARKER = "__MITV_RC__"
        private val EXIT_REGEX = Regex("__MITV_RC__(\\d+)__\\s*$")
    }
}
