package com.ddd.ubuplasma

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/**
 * Lance des commandes dans le rootfs Ubuntu via PRoot.
 *
 * Le binaire PRoot (compilé pour arm64) est packagé comme libproot.so afin
 * d'être automatiquement extrait par Android dans nativeLibraryDir.
 */
class ProotSession(context: Context, private val log: (String) -> Unit) {

    private val rootfs: File = File(context.filesDir, "ubuntu")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val prootBin = File(nativeDir, "libproot.so")
    private val prootLoader = File(nativeDir, "libproot-loader.so")
    private val prootTmp = File(context.cacheDir, "proot-tmp")

    private fun builder(args: List<String>): ProcessBuilder = ProcessBuilder(args).apply {
        redirectErrorStream(true)
        prootTmp.mkdirs()
        environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
        environment()["PROOT_LOADER"] = prootLoader.absolutePath
        environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
    }

    fun verifyRuntime() {
        for (name in listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so")) {
            val file = File(nativeDir, name)
            check(file.isFile && file.length() > 0) { "Bibliothèque native absente : ${file.absolutePath}" }
        }
        val process = builder(listOf(prootBin.absolutePath, "--version")).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "PRoot ne démarre pas : $output" }
        log("PRoot ARM64 disponible : ${output.lineSequence().firstOrNull().orEmpty()}")
    }

    @Volatile
    var currentProcess: Process? = null
        private set

    val isRunning: Boolean
        get() = currentProcess?.isAlive == true

    fun bootstrapArgs(): List<String> = args("bash /root/bootstrap.sh 2>&1")

    fun startPlasmaArgs(width: Int, height: Int): List<String> =
        args("bash /root/start-plasma.sh $width $height 2>&1")

    private fun args(command: String): List<String> {
        if (!prootBin.exists()) {
            throw IllegalStateException(
                "Binaire PRoot introuvable (${prootBin.absolutePath}) : " +
                    "le workflow CI doit avoir copié libproot.so dans jniLibs."
            )
        }

        return listOf(
            prootBin.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            "/bin/bash", "-c", command
        )
    }

    /** Démarre la commande donnée et redirige sa sortie vers le journal. */
    fun launch(args: List<String>, onExit: (Int) -> Unit): Process {
        stop()
        log("\$ ${args.joinToString(" ")}")
        val process = builder(args).start()
        currentProcess = process

        thread(name = "proot-output") {
            try {
                process.inputStream.bufferedReader().forEachLine { line -> log(line) }
            } catch (_: Exception) {
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            log("[Session PRoot terminée, code de sortie $code]")
            onExit(code)
        }
        return process
    }

    fun stop() {
        val process = currentProcess ?: return
        currentProcess = null
        runCatching { process.destroy() }
        Thread.sleep(200)
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }
}
