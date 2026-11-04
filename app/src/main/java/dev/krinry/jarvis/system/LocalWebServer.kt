package dev.krinry.jarvis.system

import android.os.Environment
import android.util.Log
import java.io.*
import java.net.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LocalWebServer — Embedded HTTP Web Server for Jarvis.
 *
 * Allows Jarvis to host websites, web applications, dashboards, and static assets
 * directly on Sir's Android phone without requiring Node.js, Python, or root access.
 *
 * Features:
 * - Pure Java/Kotlin standard library (zero external dependencies).
 * - Multi-threaded HTTP/1.1 request handling (HTML, CSS, JS, Images, JSON).
 * - Auto-detects local Wi-Fi LAN IP so websites can be accessed by other devices on the network.
 * - Auto-serves index.html from target directory or provides default Stark web template.
 * - Full CORS headers enabled for cross-origin browser requests.
 */
object LocalWebServer {

    private const val TAG = "LocalWebServer"
    private const val DEFAULT_PORT = 8080

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val isRunningFlag = AtomicBoolean(false)

    var currentPort: Int = DEFAULT_PORT
        private set
    var servedDirectory: File? = null
        private set

    fun isRunning(): Boolean = isRunningFlag.get()

    /**
     * Resolves project directory. If relative path given, puts it inside /sdcard/Download/JarvisProjects/<path>
     */
    fun resolveWorkspaceDir(subPath: String? = null): File {
        val baseDir = try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloads, "JarvisProjects")
        } catch (_: Exception) {
            File("/sdcard/Download/JarvisProjects")
        }

        val target = if (subPath.isNullOrBlank()) {
            baseDir
        } else if (subPath.startsWith("/")) {
            File(subPath)
        } else {
            File(baseDir, subPath)
        }

        if (!target.exists()) {
            target.mkdirs()
        }
        return target
    }

    /**
     * Starts the HTTP web server serving files from [dirPath] on [requestedPort].
     */
    @Synchronized
    fun start(dirPath: String? = null, requestedPort: Int = DEFAULT_PORT): Pair<Boolean, String> {
        val targetDir = resolveWorkspaceDir(dirPath)

        // Ensure an index.html exists so opening the URL always displays content
        val indexFile = File(targetDir, "index.html")
        if (!indexFile.exists() && (!targetDir.exists() || (targetDir.list()?.isEmpty() == true))) {
            targetDir.mkdirs()
            writeDefaultIndexPage(indexFile)
        }

        if (isRunningFlag.get()) {
            if (servedDirectory?.canonicalPath == targetDir.canonicalPath && currentPort == requestedPort) {
                val lanIp = getLanIpAddress() ?: "localhost"
                return Pair(true, "✅ Web server already running on port $currentPort!\nLocal URL: http://localhost:$currentPort\nNetwork URL: http://$lanIp:$currentPort\nServing: ${targetDir.absolutePath}")
            }
            // Stop existing instance to switch directory or port
            stop()
        }

        var port = requestedPort
        var boundSocket: ServerSocket? = null

        // Try ports from requestedPort to requestedPort + 10
        for (p in port..(port + 10)) {
            try {
                boundSocket = ServerSocket(p)
                port = p
                break
            } catch (_: IOException) {
                Log.w(TAG, "Port $p is busy, trying next port")
            }
        }

        if (boundSocket == null) {
            return Pair(false, "❌ Could not bind server to port $requestedPort or nearby ports.")
        }

        serverSocket = boundSocket
        currentPort = port
        servedDirectory = targetDir
        isRunningFlag.set(true)
        executor = Executors.newCachedThreadPool()

        val lanIp = getLanIpAddress() ?: "localhost"

        executor?.execute {
            Log.i(TAG, "🚀 LocalWebServer listening on port $currentPort (Serving ${targetDir.absolutePath})")
            while (isRunningFlag.get() && !boundSocket.isClosed) {
                try {
                    val clientSocket = boundSocket.accept()
                    executor?.execute {
                        handleClient(clientSocket, targetDir)
                    }
                } catch (e: SocketException) {
                    // Socket closed normally on stop
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error accepting client connection", e)
                }
            }
        }

        return Pair(
            true,
            "✅ Local Web Server online, Sir!\n" +
            "• Local URL: http://localhost:$currentPort\n" +
            "• Network URL: http://$lanIp:$currentPort\n" +
            "• Directory: ${targetDir.absolutePath}"
        )
    }

    /**
     * Stops the running HTTP server.
     */
    @Synchronized
    fun stop(): Pair<Boolean, String> {
        if (!isRunningFlag.getAndSet(false)) {
            return Pair(true, "Server is not currently running.")
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            executor?.shutdownNow()
        } catch (_: Exception) {}
        executor = null

        val dirName = servedDirectory?.name ?: "workspace"
        servedDirectory = null

        Log.i(TAG, "⏹ LocalWebServer stopped")
        return Pair(true, "✅ Local Web Server ($dirName on port $currentPort) stopped, Sir.")
    }

    /**
     * Returns server telemetry and active URLs.
     */
    fun getStatus(): String {
        return if (isRunningFlag.get()) {
            val lanIp = getLanIpAddress() ?: "localhost"
            "⚡ Web Server ACTIVE on port $currentPort\n" +
            "• Local URL: http://localhost:$currentPort\n" +
            "• Network URL: http://$lanIp:$currentPort\n" +
            "• Serving: ${servedDirectory?.absolutePath}"
        } else {
            "Web Server is OFFLINE."
        }
    }

    private fun handleClient(socket: Socket, rootDir: File) {
        try {
            socket.soTimeout = 10000
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = try {
                URLDecoder.decode(rawPath.split("?")[0], "UTF-8")
            } catch (_: Exception) {
                rawPath.split("?")[0]
            }

            // CORS Preflight
            if (method == "OPTIONS") {
                val headers = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS, HEAD\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(headers.toByteArray(Charsets.UTF_8))
                output.flush()
                return
            }

            var targetFile = File(rootDir, if (path == "/" || path.isBlank()) "index.html" else path.removePrefix("/"))
            if (targetFile.isDirectory) {
                targetFile = File(targetFile, "index.html")
            }

            // Security check: directory traversal protection
            val canonicalRoot = rootDir.canonicalPath
            val canonicalTarget = targetFile.canonicalPath
            if (!canonicalTarget.startsWith(canonicalRoot) || !targetFile.exists() || targetFile.isDirectory) {
                sendNotFound(output, path)
                return
            }

            val mime = getMimeType(targetFile.extension)
            val fileLength = targetFile.length()

            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $fileLength\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Connection: close\r\n\r\n"

            output.write(header.toByteArray(Charsets.UTF_8))

            if (method != "HEAD") {
                FileInputStream(targetFile).use { fis ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.d(TAG, "Client socket handled: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendNotFound(output: OutputStream, path: String) {
        val html = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>404 - J.A.R.V.I.S. Web Server</title>
<style>
body { background: #070B14; color: #00E5FF; font-family: monospace; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; text-align: center; }
h1 { font-size: 2.5rem; text-shadow: 0 0 20px #00E5FF; margin-bottom: 0.5rem; }
p { color: #8BE9FD; font-size: 1.1rem; }
.card { border: 1px solid rgba(0,229,255,0.3); padding: 2rem; border-radius: 12px; background: rgba(0,229,255,0.05); }
</style>
</head>
<body>
<div class="card">
<h1>404 NOT FOUND</h1>
<p>The requested resource <code>$path</code> does not exist on this server, Sir.</p>
<p style="color: #6272A4; font-size: 0.9rem;">J.A.R.V.I.S. Local Web Server • Made by Krinry</p>
</div>
</body>
</html>"""
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeDefaultIndexPage(file: File) {
        try {
            file.parentFile?.mkdirs()
            val defaultHtml = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>J.A.R.V.I.S. • Web Application</title>
    <style>
        :root {
            --cyan: #00E5FF;
            --blue: #0077FF;
            --bg: #070B14;
            --card-bg: rgba(10, 20, 36, 0.8);
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: var(--bg);
            color: #E2E8F0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 1.5rem;
            background-image: radial-gradient(circle at 50% 20%, rgba(0, 229, 255, 0.15), transparent 60%);
        }
        .container {
            max-width: 650px;
            width: 100%;
            background: var(--card-bg);
            border: 1px solid rgba(0, 229, 255, 0.3);
            border-radius: 16px;
            padding: 2.5rem;
            box-shadow: 0 0 35px rgba(0, 229, 255, 0.2);
            text-align: center;
            backdrop-filter: blur(10px);
        }
        .reactor-badge {
            display: inline-block;
            width: 64px;
            height: 64px;
            border-radius: 50%;
            border: 2px solid var(--cyan);
            box-shadow: 0 0 20px var(--cyan);
            margin-bottom: 1.5rem;
            position: relative;
        }
        .reactor-badge::after {
            content: '';
            position: absolute;
            top: 50%; left: 50%;
            transform: translate(-50%, -50%);
            width: 24px; height: 24px;
            background: var(--cyan);
            border-radius: 50%;
            box-shadow: 0 0 15px var(--cyan);
        }
        h1 {
            color: var(--cyan);
            font-size: 2rem;
            letter-spacing: 2px;
            margin-bottom: 0.5rem;
            text-transform: uppercase;
        }
        .subtitle {
            color: #94A3B8;
            font-size: 1rem;
            margin-bottom: 2rem;
        }
        .status-box {
            background: rgba(0, 229, 255, 0.05);
            border: 1px dashed rgba(0, 229, 255, 0.4);
            border-radius: 8px;
            padding: 1rem;
            margin-bottom: 2rem;
            font-family: monospace;
            color: var(--cyan);
            font-size: 0.95rem;
        }
        .btn {
            background: linear-gradient(135deg, var(--cyan), var(--blue));
            color: #070B14;
            font-weight: bold;
            padding: 0.8rem 1.8rem;
            border-radius: 8px;
            border: none;
            cursor: pointer;
            text-transform: uppercase;
            letter-spacing: 1px;
            transition: transform 0.2s, box-shadow 0.2s;
        }
        .btn:hover {
            transform: translateY(-2px);
            box-shadow: 0 0 20px rgba(0, 229, 255, 0.5);
        }
        footer {
            margin-top: 2rem;
            font-size: 0.8rem;
            color: #64748B;
            letter-spacing: 1px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="reactor-badge"></div>
        <h1>J.A.R.V.I.S. Live Host</h1>
        <p class="subtitle">Personal Operating System & Autonomous Developer Agent</p>
        <div class="status-box">
            STATUS: ACTIVE • HOSTED LOCALLY ON ANDROID<br>
            READY FOR SIR'S DEPLOYMENT
        </div>
        <button class="btn" onclick="alert('J.A.R.V.I.S. systems fully operational, Sir!')">Run System Diagnostics</button>
        <footer>JARVIS BY KRINRY • STARK SYSTEMS ONLINE</footer>
    </div>
</body>
</html>"""
            file.writeText(defaultHtml, Charsets.UTF_8)
            Log.d(TAG, "Generated default index.html at ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write default index.html", e)
        }
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "webp" -> "image/webp"
            "txt", "md", "log" -> "text/plain; charset=utf-8"
            "pdf" -> "application/pdf"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
    }

    fun getLanIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving LAN IP", e)
        }
        return null
    }
}
