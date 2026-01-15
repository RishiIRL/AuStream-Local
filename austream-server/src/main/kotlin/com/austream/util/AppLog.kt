package com.austream.util

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AppLog {
    private val tsFormatter = DateTimeFormatter
        .ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private fun logDir(): Path {
        val localAppData = System.getenv("LOCALAPPDATA")
        val base = if (!localAppData.isNullOrBlank()) {
            Paths.get(localAppData)
        } else {
            Paths.get(System.getProperty("user.home"), ".austream")
        }
        return base.resolve("AuStream").resolve("logs")
    }

    fun logFile(): Path = logDir().resolve("austream-server.log")

    @Synchronized
    fun info(message: String) = write("INFO", message, null)

    @Synchronized
    fun warn(message: String, throwable: Throwable? = null) = write("WARN", message, throwable)

    @Synchronized
    fun error(message: String, throwable: Throwable? = null) = write("ERROR", message, throwable)

    private fun write(level: String, message: String, throwable: Throwable?) {
        try {
            Files.createDirectories(logDir())
            val ts = tsFormatter.format(Instant.now())
            val thread = Thread.currentThread().name
            val sb = StringBuilder()
                .append(ts)
                .append(" [")
                .append(level)
                .append("] ")
                .append(thread)
                .append(" - ")
                .append(message)
                .append('\n')

            if (throwable != null) {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                sb.append(sw.toString()).append('\n')
            }

            Files.write(
                logFile(),
                sb.toString().toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            )
        } catch (_: Exception) {
            // Avoid recursive failures if logging itself breaks.
        }
    }
}
