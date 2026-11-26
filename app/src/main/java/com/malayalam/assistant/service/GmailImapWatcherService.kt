package com.malayalam.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.MainActivity
import com.malayalam.assistant.R
import com.malayalam.assistant.ai.EmbeddingMatcherEngine
import com.malayalam.assistant.audio.TriggerDeduplicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ImapMailHeader(
    val uid: Long,
    val from: String,
    val subject: String,
    val snippet: String
) {
    fun combinedSearchText(): String = "$subject $from $snippet".replace(Regex("\\s+"), " ").trim()
}

/**
 * Minimal, dependency-free SSL IMAP client (`imap.gmail.com:993`) engineered for 2 GB RAM / Android Go:
 *  - Zero JavaMail / Jakarta Mail JAR bloat (`< 200 KB` transient buffer).
 *  - Uses `BODY.PEEK[...]` so unread badges in the user's Gmail app are never disturbed.
 *  - Tracks `UID` monotonically so old emails are NEVER replayed.
 */
object GmailImapClient {
    private const val IMAP_HOST = "imap.gmail.com"
    private const val IMAP_PORT = 993
    private const val SOCKET_TIMEOUT_MS = 15_000

    suspend fun pollNewUnseenMails(
        email: String,
        appPassword: String,
        lastSeenUid: Long
    ): Pair<Long, List<ImapMailHeader>> = withContext(Dispatchers.IO) {
        withImapSession(email, appPassword) { reader, writer ->
            val existsAndUidNext = selectInbox(reader, writer)
            val currentMaxHint = (existsAndUidNext.second - 1L).coerceAtLeast(0L)

            // First-time baseline: record current highest UID and DO NOT replay existing inbox mail!
            if (lastSeenUid <= 0L) {
                val allUids = searchUids(reader, writer, "UID SEARCH ALL")
                val baseline = allUids.maxOrNull() ?: currentMaxHint.coerceAtLeast(1L)
                return@withImapSession baseline to emptyList()
            }

            val unseenUids = searchUids(reader, writer, "UID SEARCH UID ${lastSeenUid + 1}:* UNSEEN")
                .filter { it > lastSeenUid }
                .sorted()

            if (unseenUids.isEmpty()) {
                return@withImapSession lastSeenUid to emptyList()
            }

            // Cap per-poll fetch to newest 3 unseen messages to stay instant & light on 2 GB RAM
            val targetUids = unseenUids.takeLast(3)
            val headers = targetUids.mapNotNull { uid ->
                fetchMailByUid(reader, writer, uid)
            }
            val highestUid = unseenUids.maxOrNull() ?: lastSeenUid
            highestUid to headers
        }
    }

    suspend fun testConnectionAndFetchLatest(
        email: String,
        appPassword: String
    ): Pair<Long, ImapMailHeader?> = withContext(Dispatchers.IO) {
        withImapSession(email, appPassword) { reader, writer ->
            val (existsCount, _) = selectInbox(reader, writer)
            if (existsCount <= 0) {
                return@withImapSession 1L to null
            }
            val allUids = searchUids(reader, writer, "UID SEARCH ALL")
            val latestUid = allUids.maxOrNull() ?: return@withImapSession 1L to null
            val latestHeader = fetchMailByUid(reader, writer, latestUid)
            latestUid to latestHeader
        }
    }

    private fun <T> withImapSession(
        email: String,
        appPassword: String,
        block: (BufferedReader, BufferedWriter) -> T
    ): T {
        val cleanEmail = email.trim()
        val cleanPass = appPassword.replace(" ", "").trim()
        require(cleanEmail.contains("@") && cleanPass.length >= 8) {
            "Enter your Gmail address and 16-character Google App Password"
        }

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val rawSocket = factory.createSocket() as SSLSocket
        try {
            rawSocket.connect(InetSocketAddress(IMAP_HOST, IMAP_PORT), SOCKET_TIMEOUT_MS)
            rawSocket.soTimeout = SOCKET_TIMEOUT_MS
            rawSocket.startHandshake()

            val reader = BufferedReader(InputStreamReader(rawSocket.inputStream, StandardCharsets.UTF_8), 4096)
            val writer = BufferedWriter(OutputStreamWriter(rawSocket.outputStream, StandardCharsets.UTF_8), 2048)

            // Read greeting
            val greeting = reader.readLine() ?: error("Empty response from imap.gmail.com")
            if (!greeting.startsWith("* OK")) {
                error("Unexpected IMAP greeting: $greeting")
            }

            // Authenticate
            val loginResp = sendTaggedCommand(
                reader,
                writer,
                "A01",
                "LOGIN \"${escapeImapQuoted(cleanEmail)}\" \"${escapeImapQuoted(cleanPass)}\""
            )
            if (!loginResp.lastStatusLine.startsWith("A01 OK")) {
                error("Gmail login failed — use a 16-char Google App Password (enable 2-Step Verification)")
            }

            val result = block(reader, writer)
            runCatching { sendTaggedCommand(reader, writer, "A99", "LOGOUT") }
            return result
        } finally {
            runCatching { rawSocket.close() }
        }
    }

    private fun selectInbox(reader: BufferedReader, writer: BufferedWriter): Pair<Int, Long> {
        val resp = sendTaggedCommand(reader, writer, "A02", "SELECT INBOX")
        if (!resp.lastStatusLine.startsWith("A02 OK")) {
            error("Could not SELECT INBOX")
        }
        var exists = 0
        var uidNext = 1L
        for (line in resp.lines) {
            Regex("""\*\s+(\d+)\s+EXISTS""", RegexOption.IGNORE_CASE).find(line)?.let {
                exists = it.groupValues[1].toIntOrNull() ?: exists
            }
            Regex("""UIDNEXT\s+(\d+)""", RegexOption.IGNORE_CASE).find(line)?.let {
                uidNext = it.groupValues[1].toLongOrNull() ?: uidNext
            }
        }
        return exists to uidNext
    }

    private fun searchUids(reader: BufferedReader, writer: BufferedWriter, command: String): List<Long> {
        val resp = sendTaggedCommand(reader, writer, "A03", command)
        val out = mutableListOf<Long>()
        for (line in resp.lines) {
            if (line.startsWith("* SEARCH", ignoreCase = true)) {
                val parts = line.substringAfter("SEARCH", "").trim().split(Regex("\\s+"))
                for (p in parts) {
                    p.toLongOrNull()?.let { out.add(it) }
                }
            }
        }
        return out
    }

    private fun fetchMailByUid(
        reader: BufferedReader,
        writer: BufferedWriter,
        uid: Long
    ): ImapMailHeader? {
        val resp = sendTaggedCommand(
            reader,
            writer,
            "F$uid",
            "UID FETCH $uid (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT)] BODY.PEEK[TEXT]<0.260>)"
        )
        if (!resp.lastStatusLine.startsWith("F$uid OK")) return null

        var from = ""
        var subject = ""
        val bodyBuilder = StringBuilder()
        var inHeaders = true

        for (line in resp.lines) {
            when {
                line.startsWith("* ") && line.contains("FETCH", ignoreCase = true) -> {
                    inHeaders = true
                }
                line.startsWith("From:", ignoreCase = true) -> {
                    from = decodeMimeHeader(line.substringAfter(":").trim())
                }
                line.startsWith("Subject:", ignoreCase = true) -> {
                    subject = decodeMimeHeader(line.substringAfter(":").trim())
                }
                line.isBlank() -> {
                    inHeaders = false
                }
                !inHeaders && line != ")" -> {
                    if (bodyBuilder.length < 260) {
                        bodyBuilder.append(line).append(' ')
                    }
                }
            }
        }

        val cleanSnippet = bodyBuilder.toString()
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("=[0-9A-Fa-f]{2}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)

        return ImapMailHeader(
            uid = uid,
            from = from,
            subject = subject,
            snippet = cleanSnippet
        )
    }

    private data class ImapResponse(
        val lines: List<String>,
        val lastStatusLine: String
    )

    private fun sendTaggedCommand(
        reader: BufferedReader,
        writer: BufferedWriter,
        tag: String,
        command: String
    ): ImapResponse {
        writer.write("$tag $command\r\n")
        writer.flush()

        val lines = ArrayList<String>(16)
        while (true) {
            val line = reader.readLine() ?: error("IMAP connection closed unexpectedly")
            if (line.startsWith("$tag ")) {
                return ImapResponse(lines = lines, lastStatusLine = line)
            }
            if (lines.size < 120) {
                lines.add(line)
            }
        }
    }

    private fun escapeImapQuoted(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Decodes RFC-2047 encoded-words (`=?UTF-8?B?...?=` and `=?UTF-8?Q?...?=`) so Malayalam & UTF-8
     * email subjects/senders match cleanly.
     */
    fun decodeMimeHeader(raw: String): String {
        val regex = Regex("""=\?([^?]+)\?([BbQq])\?([^?]+)\?=""")
        if (!regex.containsMatchIn(raw)) return raw
        return regex.replace(raw) { match ->
            val charset = runCatching { java.nio.charset.Charset.forName(match.groupValues[1]) }
                .getOrDefault(StandardCharsets.UTF_8)
            val encoding = match.groupValues[2].uppercase()
            val payload = match.groupValues[3]
            runCatching {
                if (encoding == "B") {
                    String(Base64.decode(payload, Base64.DEFAULT), charset)
                } else {
                    val qp = payload.replace('_', ' ')
                    val out = java.io.ByteArrayOutputStream()
                    var i = 0
                    while (i < qp.length) {
                        val ch = qp[i]
                        if (ch == '=' && i + 2 < qp.length) {
                            val hex = qp.substring(i + 1, i + 3)
                            val byteVal = hex.toIntOrNull(16)
                            if (byteVal != null) {
                                out.write(byteVal)
                                i += 3
                                continue
                            }
                        }
                        out.write(ch.code)
                        i++
                    }
                    String(out.toByteArray(), charset)
                }
            }.getOrDefault(payload)
        }.replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Lightweight Foreground Service polling `imap.gmail.com:993` every 20 seconds when enabled.
 * Specifically solves incoming Gmail detection on **2 GB RAM / Android (Go edition)** phones
 * where `NotificationListenerService` is blocked by the OS.
 */
class GmailDirectWatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWatcher()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification("Watching Gmail inbox for Malayalam dialogue reactions")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        if (pollJob?.isActive != true) {
            pollJob = scope.launch { runPollingLoop() }
        }
        return START_STICKY
    }

    private suspend fun runPollingLoop() {
        val graph = AppGraph.from(applicationContext)
        while (scope.isActive) {
            val settings = graph.prefs.snapshot()
            if (!settings.gmailWatcherEnabled) {
                stopWatcher()
                return
            }

            val email = settings.gmailAddress
            val pass = graph.prefs.getDecryptedGmailAppPassword()
            if (email.isBlank() || pass.isBlank()) {
                graph.prefs.updateGmailWatcherProgress(null, "Missing Gmail address or 16-char App Password")
                delay(POLL_INTERVAL_MS)
                continue
            }

            runCatching {
                val (newHighestUid, unseenHeaders) = GmailImapClient.pollNewUnseenMails(
                    email = email,
                    appPassword = pass,
                    lastSeenUid = settings.gmailLastSeenUid
                )

                // Always persist highest UID FIRST so an email can NEVER replay!
                val statusNote = if (settings.gmailLastSeenUid <= 0L) {
                    "Connected • Ready for new emails (UID #$newHighestUid)"
                } else if (unseenHeaders.isNotEmpty()) {
                    "Checked ${unseenHeaders.size} new mail(s) (UID #$newHighestUid)"
                } else {
                    "Active • Watching $email (UID #$newHighestUid)"
                }
                graph.prefs.updateGmailWatcherProgress(newHighestUid, statusNote)

                for (mail in unseenHeaders) {
                    val reactionNote = graph.incomingMessageReactor.reactToIncoming(
                        source = "Gmail",
                        sourceKey = "gmail_uid_${mail.uid}",
                        sender = mail.from,
                        messageText = "${mail.subject} ${mail.snippet}",
                        appPackageHint = "com.google.android.gm"
                    )
                    if (!reactionNote.isNullOrBlank()) {
                        graph.prefs.updateGmailWatcherProgress(
                            mail.uid,
                            "$reactionNote • ${mail.subject.take(36)}"
                        )
                    }
                }
            }.onFailure { err ->
                graph.prefs.updateGmailWatcherProgress(
                    null,
                    "Connection check: ${err.message?.take(70) ?: "Retrying in 20s"}"
                )
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private fun stopWatcher() {
        pollJob?.cancel()
        pollJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gmail Direct Watcher",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Lightweight IMAP watcher for Malayalam dialogue reactions on Android Go / 2 GB RAM"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_spark)
            .setContentTitle("Malayalam Gmail Watcher")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gmail_direct_watcher_channel"
        private const val NOTIF_ID = 4102
        private const val POLL_INTERVAL_MS = 20_000L
        private const val ACTION_STOP = "com.malayalam.assistant.STOP_GMAIL_WATCHER"

        fun syncServiceState(context: Context, enabled: Boolean) {
            val appCtx = context.applicationContext
            if (enabled) {
                val intent = Intent(appCtx, GmailDirectWatcherService::class.java)
                runCatching { ContextCompat.startForegroundService(appCtx, intent) }
            } else {
                val stopIntent = Intent(appCtx, GmailDirectWatcherService::class.java).apply {
                    action = ACTION_STOP
                }
                runCatching { appCtx.stopService( Intent(appCtx, GmailDirectWatcherService::class.java) ) }
                runCatching { appCtx.startService(stopIntent) }
            }
        }
    }
}
