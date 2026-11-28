package com.ddd.ubuplasma.vnc

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Client VNC (protocole RFB 3.8) minimaliste.
 *
 * Supporte l'authentification "None", le format de pixels 32bpp et
 * l'encodage Raw, ce qui suffit pour Xvnc en loopback sur l'appareil.
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onFramebuffer(width: Int, height: Int, name: String)
        fun onUpdate(x: Int, y: Int, w: Int, h: Int)
        fun onError(message: String)
        fun onDisconnected()
    }

    companion object {
        private const val ENC_RAW = 0
        private const val MSG_FRAMEBUFFER_UPDATE = 0
        private const val MSG_BELL = 2
        private const val MSG_SERVER_CUT_TEXT = 3
        private const val SECURITY_NONE = 1
        private const val CONNECT_TIMEOUT_MS = 15_000
    }

    @Volatile
    private var running = false

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    var width = 0
        private set
    var height = 0
        private set
    var framebuffer: IntArray = IntArray(0)
        private set

    val isConnected: Boolean get() = running

    /** À appeler depuis un thread de fond. */
    fun connect() {
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            this.socket = socket
            input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
            output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))

            handshake()
            running = true
            thread(name = "vnc-reader") { readLoop() }
        } catch (e: Exception) {
            listener.onError("Connexion VNC impossible : ${e.message}")
            closeQuietly()
        }
    }

    private fun handshake() {
        val input = this.input ?: throw IOException("flux fermé")
        val out = output ?: throw IOException("flux fermé")

        val version = ByteArray(12)
        input.readFully(version)
        val versionString = String(version, Charsets.US_ASCII)
        if (!versionString.startsWith("RFB ")) {
            throw IOException("Ce n'est pas un serveur VNC")
        }
        out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        out.flush()

        val typeCount = input.readUnsignedByte()
        val securityTypes = ByteArray(typeCount)
        input.readFully(securityTypes)
        if (securityTypes.none { it.toInt() == SECURITY_NONE }) {
            throw IOException(
                "Le serveur exige une authentification VNC non supportée " +
                    "(types: ${securityTypes.joinToString(", ") { it.toInt().toString() }})"
            )
        }
        out.write(SECURITY_NONE)
        out.flush()

        val securityResult = input.readInt()
        if (securityResult != 0) {
            throw IOException("Authentification refusée par le serveur")
        }

        out.write(1) // ClientInit : shared = true
        out.flush()

        width = input.readUnsignedShort()
        height = input.readUnsignedShort()
        val pixelFormat = ByteArray(16)
        input.readFully(pixelFormat)
        val nameLength = input.readInt()
        val nameBytes = ByteArray(nameLength)
        if (nameLength > 0) input.readFully(nameBytes)
        framebuffer = IntArray(width * height)
        listener.onFramebuffer(width, height, String(nameBytes, Charsets.ISO_8859_1))

        setPixelFormat()
        setEncodings(intArrayOf(ENC_RAW))
        requestUpdate(incremental = false)
    }

    private fun setPixelFormat() {
        val out = output ?: return
        out.write(0) // SetPixelFormat
        out.write(0); out.write(0); out.write(0)
        out.write(32)  // bits per pixel
        out.write(24)  // depth
        out.write(0)   // little endian
        out.write(1)   // true color
        out.writeShort(255) // red max
        out.writeShort(255) // green max
        out.writeShort(255) // blue max
        out.write(16)  // red shift
        out.write(8)   // green shift
        out.write(0)   // blue shift
        out.write(0); out.write(0); out.write(0)
        out.flush()
    }

    private fun setEncodings(encodings: IntArray) {
        val out = output ?: return
        out.write(2) // SetEncodings
        out.write(0)
        out.writeShort(encodings.size)
        for (encoding in encodings) {
            out.writeInt(encoding)
        }
        out.flush()
    }

    fun requestUpdate(incremental: Boolean) {
        val out = output ?: return
        out.write(3) // FramebufferUpdateRequest
        out.write(if (incremental) 1 else 0)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(width)
        out.writeShort(height)
        out.flush()
    }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val out = output ?: return
        out.write(5) // PointerEvent
        out.write(buttonMask)
        out.writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0)))
        out.writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
        out.flush()
    }

    fun sendKeyEvent(keysym: Int, down: Boolean) {
        val out = output ?: return
        out.write(4) // KeyEvent
        out.write(if (down) 1 else 0)
        out.write(0); out.write(0)
        out.writeInt(keysym)
        out.flush()
    }

    private fun readLoop() {
        val input = this.input
        try {
            while (running && input != null) {
                when (val type = input.readUnsignedByte()) {
                    MSG_FRAMEBUFFER_UPDATE -> readFramebufferUpdate(input)
                    MSG_BELL -> Unit
                    MSG_SERVER_CUT_TEXT -> readServerCutText(input)
                    else -> throw IOException("Message RFB inattendu : $type")
                }
                requestUpdate(incremental = true)
            }
        } catch (_: Exception) {
        } finally {
            val wasRunning = running
            closeQuietly()
            if (wasRunning) listener.onDisconnected()
        }
    }

    private fun readFramebufferUpdate(input: DataInputStream) {
        input.readUnsignedByte() // padding
        val rectCount = input.readUnsignedShort()
        repeat(rectCount) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()
            if (encoding != ENC_RAW) {
                throw IOException("Encodage VNC non supporté : $encoding")
            }
            readRawRect(input, x, y, w, h)
            listener.onUpdate(x, y, w, h)
        }
    }

    private fun readRawRect(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val bytes = ByteArray(w * h * 4)
        input.readFully(bytes)
        val fb = framebuffer
        var src = 0
        for (row in y until y + h) {
            var index = row * width + x
            for (col in 0 until w) {
                val blue = bytes[src].toInt() and 0xFF
                val green = bytes[src + 1].toInt() and 0xFF
                val red = bytes[src + 2].toInt() and 0xFF
                fb[index++] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                src += 4
            }
        }
    }

    private fun readServerCutText(input: DataInputStream) {
        input.skipBytes(3)
        val length = input.readInt()
        if (length > 0 && length < 10_000_000) {
            var remaining = length
            while (remaining > 0) {
                remaining -= input.skipBytes(remaining)
            }
        }
    }

    private fun closeQuietly() {
        running = false
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    fun close() {
        closeQuietly()
    }
}
