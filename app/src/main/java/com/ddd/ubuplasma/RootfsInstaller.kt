package com.ddd.ubuplasma

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Télécharge et extrait un rootfs Ubuntu ARM64 dans les fichiers privés de
 * l'application, puis installe les scripts de démarrage (Plasma + Xvnc).
 */
class RootfsInstaller(
    private val context: Context,
    private val log: (String) -> Unit
) {

    companion object {
        private const val ROOTFS_URL =
            "https://cloud-images.ubuntu.com/releases/22.04/release/ubuntu-22.04-server-cloudimg-arm64-root.tar.xz"
        private const val BUFFER_SIZE = 64 * 1024
    }

    val rootfsDir: File get() = File(context.filesDir, "ubuntu")
    val rootHome: File get() = File(rootfsDir, "root")

    val isInstalled: Boolean
        get() = File(rootfsDir, "bin/bash").exists() && File(rootHome, "start-plasma.sh").exists()

    suspend fun install() = withContext(Dispatchers.IO) {
        log("Téléchargement du rootfs Ubuntu ARM64…")
        val archive = download()
        try {
            log("Extraction du rootfs dans ${rootfsDir.absolutePath}…")
            extract(archive)
            writeEtcFiles()
            copyScripts()
            log("Rootfs Ubuntu installé.")
        } finally {
            archive.delete()
        }
    }

    private fun download(): File {
        val destination = File(context.cacheDir, "ubuntu-rootfs.tar.xz")
        val connection = URL(ROOTFS_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.connect()

        val code = connection.responseCode
        if (code !in 200..299) {
            throw java.io.IOException("Téléchargement échoué (HTTP $code)")
        }

        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0 && copied % (10L * BUFFER_SIZE) < read) {
                        log(String.format("  %d Mo / %d Mo", copied / (1024 * 1024), total / (1024 * 1024)))
                    }
                }
            }
        }
        connection.disconnect()
        return destination
    }

    private fun extract(archive: File) {
        rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()

        TarArchiveInputStream(XZCompressorInputStream(archive.inputStream())).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                val target = resolvedTarget(entry.name) ?: continue

                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output -> tar.copyTo(output, BUFFER_SIZE) }
                applyMode(target, entry.mode)
            }
        }
    }

    private fun resolvedTarget(name: String): File? {
        val normalized = name.removePrefix("./")
        if (normalized.isEmpty()) return null
        if (normalized.contains("..") || normalized.startsWith("/")) {
            log("Entrée ignorée (chemin suspect) : $name")
            return null
        }
        return File(rootfsDir, normalized)
    }

    private fun applyMode(target: File, mode: Int) {
        target.setReadable(true, false)
        if (mode and 0b100_000_000 != 0) target.setExecutable(true)
        if (mode and 0b010_000_000 == 0) target.setWritable(false, false)
    }

    private fun writeEtcFiles() {
        File(rootfsDir, "etc/resolv.conf").writeText(
            """
            nameserver 8.8.8.8
            nameserver 1.1.1.1
            """.trimIndent() + "\n"
        )
        File(rootfsDir, "etc/hosts").writeText(
            """
            127.0.0.1 localhost
            127.0.1.1 ubuntu-plasma
            ::1 localhost
            """.trimIndent() + "\n"
        )
        File(rootfsDir, "etc/hostname").writeText("ubuntu-plasma\n")
    }

    fun copyScripts() {
        rootHome.mkdirs()
        for (script in listOf("bootstrap.sh", "start-plasma.sh")) {
            val target = File(rootHome, script)
            context.assets.open("scripts/$script").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target.setExecutable(true)
        }
    }
}
