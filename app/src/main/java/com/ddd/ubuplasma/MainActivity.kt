package com.ddd.ubuplasma

import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ddd.ubuplasma.vnc.VncClient
import com.ddd.ubuplasma.vnc.VncView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity(), VncView.Callback {

    companion object {
        private const val VNC_HOST = "127.0.0.1"
        private const val VNC_PORT = 5901
        private const val PLASMA_WIDTH = 1280
        private const val PLASMA_HEIGHT = 720
        private const val PORT_WAIT_TIMEOUT_MS = 120_000L
    }

    private lateinit var installer: RootfsInstaller
    private lateinit var session: ProotSession

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var vncContainer: FrameLayout
    private lateinit var vncView: VncView
    private lateinit var keyInput: EditText
    private lateinit var btnInstall: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnLog: Button

    private var vncClient: VncClient? = null
    private var busy = false

    private val logLock = Any()
    private val logBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.SUPPORTED_ABIS.none { it.startsWith("arm64") }) {
            toast(getString(R.string.not_arm64))
        }

        installer = RootfsInstaller(applicationContext, ::appendLog)
        session = ProotSession(applicationContext, ::appendLog)

        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
        vncContainer = findViewById(R.id.vnc_container)
        keyInput = findViewById(R.id.key_input)
        btnInstall = findViewById(R.id.btn_install)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnLog = findViewById(R.id.btn_log)

        vncView = VncView(this)
        vncContainer.addView(vncView)

        btnInstall.setOnClickListener { installUbuntu() }
        btnStart.setOnClickListener { startPlasma() }
        btnStop.setOnClickListener { stopSession() }
        btnLog.setOnClickListener {
            val showLog = logScroll.visibility == View.GONE
            logScroll.visibility = if (showLog) View.VISIBLE else View.GONE
            vncContainer.visibility = if (showLog) View.GONE else View.VISIBLE
        }

        setupKeyboard()

        btnStart.isEnabled = installer.isInstalled
        appendLog(
            "Bienvenue ! Étape 1 : bouton « Installer Ubuntu » (télécharge le rootfs " +
                "ARM64 et installe KDE Plasma + Xvnc, 10 à 30 min). " +
                "Étape 2 : bouton « Démarrer Plasma »."
        )
    }

    private fun setupKeyboard() {
        var sending = false
        keyInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (sending || s.isNullOrEmpty()) return
                val keysym = keysymFor(s.last())
                sending = true
                keyInput.setText("")
                sending = false
                vncClient?.let {
                    it.sendKeyEvent(keysym, true)
                    it.sendKeyEvent(keysym, false)
                }
            }
        })
        keyInput.setOnKeyListener { _, keyCode, event ->
            val keysym = when (keyCode) {
                android.view.KeyEvent.KEYCODE_DEL -> 0xFF08
                android.view.KeyEvent.KEYCODE_ENTER -> 0xFF0D
                android.view.KeyEvent.KEYCODE_TAB -> 0xFF09
                else -> return@setOnKeyListener false
            }
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                vncClient?.sendKeyEvent(keysym, true)
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                vncClient?.sendKeyEvent(keysym, false)
            }
            true
        }
    }

    private fun keysymFor(char: Char): Int = when (char) {
        '\n', '\r' -> 0xFF0D
        '\b' -> 0xFF08
        '\t' -> 0xFF09
        else -> char.code
    }

    private fun installUbuntu() {
        if (busy) return
        busy = true
        btnInstall.isEnabled = false
        toast(getString(R.string.installing))

        lifecycleScope.launch {
            try {
                installer.install()
                runBootstrap()
            } catch (e: Exception) {
                appendLog("ERREUR installation : ${e.message}")
                toast("Installation échouée : ${e.message}")
            } finally {
                busy = false
                btnInstall.isEnabled = !installer.isInstalled
                btnStart.isEnabled = installer.isInstalled
            }
        }
    }

    private suspend fun runBootstrap() {
        appendLog("Installation de KDE Plasma et Xvnc dans Ubuntu (peut être long)…")
        val args = session.bootstrapArgs()
        withContext(Dispatchers.IO) {
            val process = session.launch(args) { }
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun startPlasma() {
        if (busy || session.isRunning) return
        busy = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        appendLog("Démarrage du bureau Plasma…")

        lifecycleScope.launch {
            try {
                val args = session.startPlasmaArgs(PLASMA_WIDTH, PLASMA_HEIGHT)
                withContext(Dispatchers.IO) {
                    session.launch(args) { }
                }
                appendLog("Attente du serveur VNC (port $VNC_PORT)…")
                val ready = withContext(Dispatchers.IO) { waitForVncPort() }
                if (!ready) {
                    appendLog("Délai dépassé : le serveur VNC n'a pas répondu.")
                    toast("Impossible de joindre le serveur VNC")
                    return@launch
                }
                connectVnc()
            } catch (e: Exception) {
                appendLog("ERREUR démarrage : ${e.message}")
                toast("Démarrage échoué : ${e.message}")
            } finally {
                busy = false
                btnStart.isEnabled = !session.isRunning
            }
        }
    }

    private suspend fun waitForVncPort(): Boolean {
        val deadline = System.currentTimeMillis() + PORT_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && session.isRunning) {
            val open = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(VNC_HOST, VNC_PORT), 1500)
                    }
                    true
                }.getOrDefault(false)
            }
            if (open) return true
            delay(2000)
        }
        return false
    }

    private fun connectVnc() {
        val client = VncClient(VNC_HOST, VNC_PORT, vncView)
        vncClient = client
        vncView.attach(client)
        lifecycleScope.launch(Dispatchers.IO) { client.connect() }

        logScroll.visibility = View.GONE
        vncContainer.visibility = View.VISIBLE
        toast(getString(R.string.connecting))
    }

    private fun stopSession() {
        runCatching { vncClient?.close() }
        vncClient = null
        vncView.detach()
        vncContainer.visibility = View.GONE
        logScroll.visibility = View.VISIBLE
        session.stop()
        btnStart.isEnabled = installer.isInstalled
        btnStop.isEnabled = false
        appendLog("Session arrêtée.")
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            synchronized(logLock) {
                logBuffer.append(line).append('\n')
                if (logBuffer.length > 200_000) {
                    logBuffer.delete(0, logBuffer.length - 100_000)
                }
                logText.text = logBuffer.toString()
            }
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onVncError(message: String) {
        appendLog("VNC : $message")
        toast(message)
    }

    override fun onVncDisconnected() {
        appendLog("Connexion VNC fermée.")
        runOnUiThread {
            vncContainer.visibility = View.GONE
            logScroll.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        runCatching { vncClient?.close() }
        vncView.detach()
        session.stop()
        super.onDestroy()
    }
}
