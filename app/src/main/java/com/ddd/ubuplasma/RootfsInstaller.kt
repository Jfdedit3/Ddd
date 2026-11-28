package com.ddd.ubuplasma

import android.content.Context
import android.system.Os
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

    private val rootfsMarker get() = File(rootfsDir, ".rootfs-v2")
    private val plasmaMarker get() = File(rootfsDir, ".plasma-ready")
    val hasRootfs: Boolean
        get() = rootfsMarker.isFile && File(rootfsDir, "usr/bin/bash").isFile
    val isInstalled: Boolean
        get() = hasRootfs && plasmaMarker.isFile

    fun markPlasmaInstalled() { plasmaMarker.writeText("ok\n") }

    suspend fun install() = withContext(Dispatchers.IO) {
        if (hasRootfs) {
            copyScripts()
            log("Rootfs existant réutilisé.")
            return@withContext
        }
        log("Téléchargement du rootfs Ubuntu ARM64…")
        val archive = download()
        try {
            log("Extraction du rootfs dans ${rootfsDir.absolutePath}…")
            extract(archive)
            writeEtcFiles()
            copyScripts()
            check(File(rootfsDir, "usr/bin/bash").isFile) { "Rootfs incomplet : bash absent" }
            rootfsMarker.writeText("ok\n")
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

        val links = mutableListOf<Triple<File, String, Boolean>>()
        TarArchiveInputStream(XZCompressorInputStream(archive.inputStream())).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                val target = resolvedTarget(entry.name) ?: continue
                when {
                    entry.isSymbolicLink || entry.isLink -> {
                        links.add(Triple(target, entry.linkName, entry.isSymbolicLink))
                    }
                    entry.isDirectory -> target.mkdirs()
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> tar.copyTo(output, BUFFER_SIZE) }
                        applyMode(target, entry.mode)
                    }
                    else -> Unit // PRoot binds /dev, /proc and /sys at runtime.
                }
            }
        }

        // Hard links first: their targets are ordinary archive files.
        for ((target, name, symbolic) in links) {
            if (symbolic) continue
            val source = resolvedTarget(name) ?: error("Lien dur invalide : $name")
            check(source.isFile) { "Cible de lien dur absente : $name" }
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            Os.link(source.absolutePath, target.absolutePath)
        }
        // Preserve relative links and make absolute guest links relative to this rootfs.
        val root = rootfsDir.toPath().toAbsolutePath().normalize()
        for ((target, name, symbolic) in links) {
            if (!symbolic) continue
            val destination = if (name.startsWith("/")) root.resolve(name.trimStart('/'))
                else target.parentFile!!.toPath().resolve(name)
            val normalized = destination.normalize()
            check(normalized.startsWith(root)) { "Lien symbolique hors rootfs : $name" }
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            val relative = target.parentFile!!.toPath().relativize(normalized).toString()
            Os.symlink(relative.ifEmpty { "." }, target.absolutePath)
        }
    }

    private fun resolvedTarget(name: String): File? {
        val root = rootfsDir.toPath().toAbsolutePath().normalize()
        if (name.startsWith("/")) return null
        val target = root.resolve(name).normalize()
        if (target == root || !target.startsWith(root)) return null
        return target.toFile()
    }

    private fun applyMode(target: File, mode: Int) {
        target.setReadable(true, false)
        if (mode and 0b100_000_000 != 0) target.setExecutable(true)
        if (mode and 0b010_000_000 == 0) target.setWritable(false, false)
    }

    private fun writeEtcFiles() {
        val resolv = File(rootfsDir, "etc/resolv.conf")
        // Cloud images may ship a dangling systemd-resolved symlink.
        runCatching { Os.unlink(resolv.absolutePath) }
        resolv.writeText(
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
