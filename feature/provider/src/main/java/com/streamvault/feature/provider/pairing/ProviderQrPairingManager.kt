package com.streamvault.feature.provider.pairing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerTransportChallenge
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.JellyfinProviderSetupCommand
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.StalkerProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.EnumMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAIRING_SESSION_MS = 5 * 60 * 1_000L
private const val PAIRING_QR_SIZE = 384
private const val MAX_FORM_BYTES = 24 * 1024
private const val TAG = "ProviderQrPairing"

@Singleton
class ProviderQrPairingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val validateAndAddProvider: ValidateAndAddProvider,
    private val providerRepository: ProviderRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private val _state = MutableStateFlow(ProviderQrPairingState())
    val state: StateFlow<ProviderQrPairingState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var timeoutJob: Job? = null
    private var activeToken: String? = null
    private var activeExpiresAtMs: Long = 0L
    private var pendingStalkerReview: PendingStalkerReview? = null

    suspend fun startPairing() {
        stopPairing()
        val host = resolveLanIpv4Address()
        if (host.isNullOrBlank()) {
            _state.value = ProviderQrPairingState(
                status = ProviderQrPairingStatus.ERROR,
                message = "Could not find a Wi-Fi/LAN IP address. Make sure the TV is connected to your network."
            )
            return
        }

        val socket = runCatching { ServerSocket(0, 8) }.getOrElse { error ->
            _state.value = ProviderQrPairingState(
                status = ProviderQrPairingStatus.ERROR,
                message = "Could not start pairing server: ${error.message ?: "unknown error"}"
            )
            return
        }
        val token = generateToken()
        val port = socket.localPort
        val url = "http://$host:$port/pair?t=$token"
        val expiresAt = System.currentTimeMillis() + PAIRING_SESSION_MS

        serverSocket = socket
        activeToken = token
        activeExpiresAtMs = expiresAt
        _state.value = ProviderQrPairingState(
            status = ProviderQrPairingStatus.READY,
            url = url,
            qrBitmap = createQrBitmap(url),
            expiresAtMs = expiresAt,
            message = "Scan from a phone on the same Wi-Fi."
        )

        acceptJob = scope.launch {
            acceptLoop(socket)
        }
        timeoutJob = scope.launch {
            kotlinx.coroutines.delay(PAIRING_SESSION_MS)
            if (_state.value.status == ProviderQrPairingStatus.READY ||
                _state.value.status == ProviderQrPairingStatus.RECEIVING
            ) {
                stopPairing("Pairing expired. Start a new QR session to try again.")
            }
        }
    }

    suspend fun stopPairing(message: String? = null) {
        val oldAcceptJob = acceptJob
        val oldTimeoutJob = timeoutJob
        acceptJob = null
        timeoutJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeToken = null
        activeExpiresAtMs = 0L
        pendingStalkerReview = null
        if (oldAcceptJob != null && oldAcceptJob !== kotlinx.coroutines.currentCoroutineContext()[Job]) {
            runCatching { oldAcceptJob.cancelAndJoin() }
        }
        if (oldTimeoutJob != null && oldTimeoutJob !== kotlinx.coroutines.currentCoroutineContext()[Job]) {
            runCatching { oldTimeoutJob.cancelAndJoin() }
        }
        _state.value = ProviderQrPairingState(
            status = if (message == null) ProviderQrPairingStatus.IDLE else ProviderQrPairingStatus.ERROR,
            message = message
        )
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            scope.launch {
                runCatching {
                    handleClient(client)
                }.onFailure { error ->
                    Log.w(TAG, "Pairing client request failed without stopping the app", error)
                    runCatching { client.close() }
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val firstLine = readHttpLine(input) ?: return
            val parts = firstLine.split(' ')
            if (parts.size < 2) {
                writeResponse(client.getOutputStream(), 400, "text/plain", "Bad request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val pathAndQuery = parts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val key = line.substringBefore(':', "").trim().lowercase(Locale.US)
                val value = line.substringAfter(':', "").trim()
                if (key.isNotBlank()) headers[key] = value
            }

            when {
                method == "GET" && pathAndQuery.startsWith("/pair") -> {
                    val token = queryParams(pathAndQuery)["t"]
                    if (!isTokenValid(token)) {
                        writeHtml(client.getOutputStream(), 403, errorPage("Pairing link expired or invalid. Start a new QR session on the TV."))
                    } else {
                        writeHtml(client.getOutputStream(), 200, formPage(token.orEmpty()))
                    }
                }
                method == "POST" && pathAndQuery in setOf("/submit", "/approve-transport", "/save-unverified") -> {
                    val form = readPostForm(input, headers)
                    val token = form["token"]
                    if (!isTokenValid(token)) {
                        writeHtml(client.getOutputStream(), 403, errorPage("Pairing session expired. Start a new QR session on the TV."))
                        return
                    }
                    _state.value = _state.value.copy(
                        status = ProviderQrPairingStatus.RECEIVING,
                        message = "Phone submitted provider details. Validating..."
                    )
                    val saveResult = when (pathAndQuery) {
                        "/submit" -> {
                            pendingStalkerReview = null
                            addProviderFromForm(form)
                        }
                        "/approve-transport" -> {
                            val review = takePendingReview() as? PendingStalkerReview.Transport
                            if (review == null) {
                                ProviderPairingSubmitResult.Error("This confirmation has expired. Submit the form again.")
                            } else if (review.challenge.reason == StalkerTransportChallengeReason.INVALID_TLS &&
                                review.challenge.proposedSpkiSha256.isNullOrBlank()
                            ) {
                                ProviderPairingSubmitResult.Error("The provider certificate could not be read safely.")
                            } else {
                                validateStalker(review.command.copy(transportGrant = review.challenge.acceptedGrant()))
                            }
                        }
                        else -> {
                            val review = takePendingReview() as? PendingStalkerReview.Verification
                            if (review == null) {
                                ProviderPairingSubmitResult.Error("This confirmation has expired. Submit the form again.")
                            } else {
                                validateStalker(review.command.copy(saveWithoutVerification = true))
                            }
                        }
                    }
                    presentSubmissionResult(client.getOutputStream(), saveResult, token.orEmpty())
                }
                else -> writeResponse(client.getOutputStream(), 404, "text/plain", "Not found")
            }
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.ISO_8859_1.name())
            if (next == '\n'.code) break
            if (bytes.size() >= 8192) throw IllegalArgumentException("HTTP request header is too long")
            if (next != '\r'.code) bytes.write(next)
        }
        return bytes.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readPostForm(input: InputStream, headers: Map<String, String>): Map<String, String> {
        val length = headers["content-length"]?.toIntOrNull() ?: return emptyMap()
        require(length in 0..MAX_FORM_BYTES) { "Pairing form is too large" }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count <= 0) break
            read += count
        }
        return parseForm(String(body, 0, read, StandardCharsets.UTF_8))
    }

    private fun takePendingReview(): PendingStalkerReview? = synchronized(this) {
        pendingStalkerReview.also { pendingStalkerReview = null }
    }

    private fun presentSubmissionResult(output: OutputStream, result: ProviderPairingSubmitResult, token: String) {
        when (result) {
            is ProviderPairingSubmitResult.Success -> {
                writeHtml(output, 200, successPage(result.providerName))
                invalidateAfterSuccess(result.providerName)
            }
            is ProviderPairingSubmitResult.TransportConsentRequired -> {
                pendingStalkerReview = PendingStalkerReview.Transport(result.command, result.challenge)
                _state.value = _state.value.copy(
                    status = ProviderQrPairingStatus.READY,
                    message = "Confirm the provider connection warning on your phone."
                )
                writeHtml(output, 200, transportConsentPage(result.challenge, token))
            }
            is ProviderPairingSubmitResult.VerificationInconclusive -> {
                pendingStalkerReview = PendingStalkerReview.Verification(result.command)
                _state.value = _state.value.copy(
                    status = ProviderQrPairingStatus.READY,
                    message = "Review the provider verification warning on your phone."
                )
                writeHtml(output, 200, verificationPage(token))
            }
            is ProviderPairingSubmitResult.Error -> {
                _state.value = _state.value.copy(status = ProviderQrPairingStatus.READY, message = result.message)
                writeHtml(output, 400, errorPage(result.message))
            }
        }
    }


    private suspend fun addProviderFromForm(form: Map<String, String>): ProviderPairingSubmitResult {
        val type = form["type"].orEmpty().lowercase(Locale.US)
        val name = form["name"].orEmpty().ifBlank {
            when (type) {
                "m3u" -> "Phone M3U Playlist"
                "stalker" -> "Phone Stalker Portal"
                "jellyfin" -> "Phone Jellyfin"
                else -> "Phone Xtream Provider"
            }
        }
        val stalkerCommand = if (type == "stalker") StalkerProviderSetupCommand(
            portalUrl = form["serverUrl"].orEmpty(),
            macAddress = form["macAddress"].orEmpty(),
            authMode = StalkerAuthMode.AUTO,
            username = form["username"].orEmpty(),
            password = form["password"].orEmpty(),
            name = name,
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            guideSourcePolicy = GuideSourcePolicy.AUTO,
            channelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED
        ) else null
        val result = when (type) {
            "m3u" -> validateAndAddProvider.addM3u(
                M3uProviderSetupCommand(
                    url = form["m3uUrl"].orEmpty(),
                    name = name,
                    epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                    guideSourcePolicy = GuideSourcePolicy.AUTO,
                    channelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED
                ),
                onProgress = ::updateProgress
            )
            "stalker" -> validateAndAddProvider.loginStalker(stalkerCommand!!, onProgress = ::updateProgress)
            "jellyfin" -> validateAndAddProvider.loginJellyfin(
                JellyfinProviderSetupCommand(
                    serverUrl = form["serverUrl"].orEmpty(),
                    username = form["username"].orEmpty(),
                    password = form["password"].orEmpty(),
                    name = name
                ),
                onProgress = ::updateProgress
            )
            else -> validateAndAddProvider.loginXtream(
                XtreamProviderSetupCommand(
                    serverUrl = form["serverUrl"].orEmpty(),
                    username = form["username"].orEmpty(),
                    password = form["password"].orEmpty(),
                    name = name,
                    epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                    xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
                    guideSourcePolicy = GuideSourcePolicy.AUTO,
                    channelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED
                ),
                onProgress = ::updateProgress
            )
        }
        return toPairingResult(result, stalkerCommand)
    }

    private suspend fun validateStalker(command: StalkerProviderSetupCommand): ProviderPairingSubmitResult =
        toPairingResult(validateAndAddProvider.loginStalker(command, onProgress = ::updateProgress), command)

    private fun toPairingResult(
        result: ValidateAndAddProviderResult,
        stalkerCommand: StalkerProviderSetupCommand?
    ): ProviderPairingSubmitResult = when (result) {
            is ValidateAndAddProviderResult.Success ->
                ProviderPairingSubmitResult.Success(result.provider.name)
            is ValidateAndAddProviderResult.SavedWithWarning ->
                ProviderPairingSubmitResult.Success(result.provider.name)
            is ValidateAndAddProviderResult.ValidationError ->
                ProviderPairingSubmitResult.Error(result.message)
            is ValidateAndAddProviderResult.TransportConsentRequired ->
                if (stalkerCommand != null) ProviderPairingSubmitResult.TransportConsentRequired(stalkerCommand, result.challenge)
                else ProviderPairingSubmitResult.Error("Review this provider's connection warning on the TV.")
            is ValidateAndAddProviderResult.VerificationInconclusive ->
                if (stalkerCommand != null) ProviderPairingSubmitResult.VerificationInconclusive(stalkerCommand)
                else ProviderPairingSubmitResult.Error(result.message)
            is ValidateAndAddProviderResult.Error ->
                ProviderPairingSubmitResult.Error(result.message)
        }

    private fun updateProgress(message: String) {
        _state.value = _state.value.copy(
            status = ProviderQrPairingStatus.RECEIVING,
            message = message
        )
    }

    private fun invalidateAfterSuccess(providerName: String) {
        runCatching { serverSocket?.close() }
        activeToken = null
        activeExpiresAtMs = 0L
        pendingStalkerReview = null
        _state.value = ProviderQrPairingState(
            status = ProviderQrPairingStatus.COMPLETE,
            message = "Provider added from phone: $providerName"
        )
    }

    private fun isTokenValid(token: String?): Boolean =
        token != null &&
            token == activeToken &&
            System.currentTimeMillis() < activeExpiresAtMs

    private fun generateToken(): String {
        val bytes = ByteArray(12)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createQrBitmap(value: String): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 1
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, PAIRING_QR_SIZE, PAIRING_QR_SIZE, hints)
        val pixels = IntArray(PAIRING_QR_SIZE * PAIRING_QR_SIZE)
        for (y in 0 until PAIRING_QR_SIZE) {
            for (x in 0 until PAIRING_QR_SIZE) {
                pixels[y * PAIRING_QR_SIZE + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(PAIRING_QR_SIZE, PAIRING_QR_SIZE, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, PAIRING_QR_SIZE, 0, 0, PAIRING_QR_SIZE, PAIRING_QR_SIZE)
        }
    }

    private fun resolveLanIpv4Address(): String? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivity?.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (isWifi) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress?.takeIf { it != 0 }
            if (ip != null) return Formatter.formatIpAddress(ip)
        }
        return NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { !it.startsWith("127.") }
    }

    private fun queryParams(pathAndQuery: String): Map<String, String> =
        pathAndQuery.substringAfter('?', "")
            .takeIf { it.isNotBlank() }
            ?.let(::parseForm)
            ?: emptyMap()

    private fun parseForm(body: String): Map<String, String> =
        body.split('&')
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val key = decode(part.substringBefore('=', ""))
                val value = decode(part.substringAfter('=', ""))
                key to value
            }
            .toMap()

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (e: IllegalArgumentException) {
        value
    }

    private fun writeHtml(output: OutputStream, status: Int, html: String) {
        writeResponse(output, status, "text/html; charset=utf-8", html)
    }

    private fun writeResponse(output: OutputStream, status: Int, contentType: String, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "OK"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = (
            "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        runCatching {
            output.write(header)
            output.write(bytes)
            output.flush()
        }.onFailure { error ->
            Log.w(TAG, "Pairing client disconnected before response was written", error)
        }
    }

    private fun formPage(token: String): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>StreamVault Pairing</title>
          <style>
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#101820;color:#f8fafc;margin:0;padding:24px}
            main{max-width:560px;margin:0 auto;background:#172635;border:1px solid #2b4258;border-radius:22px;padding:22px;box-shadow:0 18px 60px rgba(0,0,0,.35)}
            h1{margin:0 0 8px;font-size:26px} p{color:#b9c6d3;line-height:1.45}
            label{display:block;margin-top:14px;font-weight:700}
            input,select{width:100%;box-sizing:border-box;margin-top:7px;padding:13px;border-radius:12px;border:1px solid #39546d;background:#0c1620;color:#fff;font-size:16px}
            button{width:100%;margin-top:20px;padding:15px;border:0;border-radius:14px;background:#32d6a0;color:#06110d;font-weight:900;font-size:17px}
            .hint{font-size:13px;color:#93a4b5}.field-group{display:none}.field-group.active{display:block}
          </style>
        </head>
        <body>
        <main>
          <h1>Add provider to StreamVault</h1>
          <p>Enter details on your phone. They are sent directly to your TV over your local Wi-Fi only.</p>
          <form method="post" action="/submit">
            <input type="hidden" name="token" value="${token.escapeHtml()}">
            <label>Provider type</label>
            <select name="type" id="type" onchange="updateType()">
              <option value="xtream">Xtream Codes</option>
              <option value="m3u">M3U Playlist URL</option>
              <option value="stalker">Stalker / MAG Portal</option>
              <option value="jellyfin">Jellyfin</option>
            </select>
            <label>Provider name</label>
            <input name="name" placeholder="Provider Name">
            <div id="serverFields" class="field-group active">
              <label>Server / portal URL</label>
              <input name="serverUrl" placeholder="https://example.com">
              <label>Username</label>
              <input name="username" autocomplete="username">
              <label>Password</label>
              <input name="password" type="password" autocomplete="current-password">
            </div>
            <div id="m3uFields" class="field-group">
              <label>M3U playlist URL</label>
              <input name="m3uUrl" placeholder="https://example.com/get.php?...">
            </div>
            <div id="stalkerFields" class="field-group">
              <label>MAC address</label>
              <input name="macAddress" placeholder="00:1A:79:AA:BB:CC">
              <p class="hint">For credential-only portals, leave MAC blank and fill username/password above.</p>
            </div>
            <div id="jellyfinFields" class="field-group">
              <p class="hint">Enter your Jellyfin server details below, then press Quick Connect in the app.</p>
            </div>
            <button type="submit">Send to TV</button>
          </form>
        </main>
        <script>
          function updateType(){
            const t=document.getElementById('type').value;
            const showServer = t !== 'm3u';
            document.getElementById('serverFields').classList.toggle('active', showServer);
            document.getElementById('m3uFields').classList.toggle('active', t === 'm3u');
            document.getElementById('stalkerFields').classList.toggle('active', t === 'stalker');
          }
        </script>
        </body>
        </html>
    """.trimIndent()

    private fun successPage(providerName: String): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#101820;color:#f8fafc;padding:28px}main{max-width:520px;margin:auto;background:#172635;border-radius:22px;padding:24px}h1{color:#32d6a0}</style>
        </head><body><main><h1>Sent to TV</h1><p>${providerName.escapeHtml()} was added to StreamVault. You can close this page.</p></main></body></html>
    """.trimIndent()

    private fun transportConsentPage(challenge: StalkerTransportChallenge, token: String): String {
        val message = when (challenge.reason) {
            StalkerTransportChallengeReason.CLEARTEXT_HTTP ->
                "Your login and viewing requests to ${challenge.displayHost.escapeHtml()} can be read or changed by someone on the network."
            StalkerTransportChallengeReason.INVALID_TLS ->
                "The connection to ${challenge.displayHost.escapeHtml()} may be encrypted, but its identity could not be verified."
            StalkerTransportChallengeReason.ORIGIN_CHANGED ->
                "The provider redirected the connection to ${challenge.displayHost.escapeHtml()}. Review this address before continuing."
        }
        if (challenge.reason == StalkerTransportChallengeReason.INVALID_TLS &&
            challenge.proposedSpkiSha256.isNullOrBlank()
        ) return errorPage("The provider certificate could not be read safely. Check the address on the TV.")
        return reviewPage(
            title = "Review provider connection",
            message = message,
            action = "/approve-transport",
            button = "Connect anyway",
            token = token
        )
    }

    private fun verificationPage(token: String): String = reviewPage(
        title = "Live TV could not be verified",
        message = "The provider accepted your login, but the Live TV check did not finish. " +
            "You can save it with verification pending; it will remain inactive until a sync confirms playable Live TV.",
        action = "/save-unverified",
        button = "Save with verification pending",
        token = token
    )

    private fun reviewPage(title: String, message: String, action: String, button: String, token: String): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#101820;color:#f8fafc;padding:28px}main{max-width:520px;margin:auto;background:#172635;border-radius:22px;padding:24px}p{line-height:1.5}button{width:100%;padding:15px;border:0;border-radius:14px;background:#32d6a0;color:#06110d;font-size:17px;font-weight:900}</style>
        </head><body><main><h1>${title.escapeHtml()}</h1><p>$message</p>
        <form method="post" action="$action"><input type="hidden" name="token" value="${token.escapeHtml()}">
        <button type="submit">${button.escapeHtml()}</button></form>
        <p>You can close this page if you do not want to continue.</p></main></body></html>
    """.trimIndent()

    private fun errorPage(message: String): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#101820;color:#f8fafc;padding:28px}main{max-width:520px;margin:auto;background:#2a1720;border-radius:22px;padding:24px}h1{color:#ff8b8b}</style>
        </head><body><main><h1>Could not add provider</h1><p>${message.escapeHtml()}</p><p>Go back and check the details, or start a new QR session on the TV.</p></main></body></html>
    """.trimIndent()

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}

data class ProviderQrPairingState(
    val status: ProviderQrPairingStatus = ProviderQrPairingStatus.IDLE,
    val url: String? = null,
    val qrBitmap: Bitmap? = null,
    val expiresAtMs: Long = 0L,
    val message: String? = null
)

enum class ProviderQrPairingStatus {
    IDLE,
    READY,
    RECEIVING,
    COMPLETE,
    ERROR
}

private sealed interface ProviderPairingSubmitResult {
    data class Success(val providerName: String) : ProviderPairingSubmitResult
    data class Error(val message: String) : ProviderPairingSubmitResult
    data class TransportConsentRequired(
        val command: StalkerProviderSetupCommand,
        val challenge: StalkerTransportChallenge
    ) : ProviderPairingSubmitResult
    data class VerificationInconclusive(val command: StalkerProviderSetupCommand) : ProviderPairingSubmitResult
}

private sealed interface PendingStalkerReview {
    data class Transport(
        val command: StalkerProviderSetupCommand,
        val challenge: StalkerTransportChallenge
    ) : PendingStalkerReview
    data class Verification(val command: StalkerProviderSetupCommand) : PendingStalkerReview
}
