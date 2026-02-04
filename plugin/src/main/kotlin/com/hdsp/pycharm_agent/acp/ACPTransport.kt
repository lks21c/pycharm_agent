package com.hdsp.pycharm_agent.acp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Transport layer interface for ACP communication
 *
 * Abstracts the underlying transport mechanism (STDIO, WebSocket, etc.)
 * to allow for different communication strategies.
 */
interface ACPTransport : Closeable {
    /**
     * Send a JSON-RPC request and wait for response
     *
     * @param request The request to send
     * @param timeoutMs Timeout in milliseconds
     * @return The response from the server
     * @throws TimeoutException if no response within timeout
     * @throws IOException if transport error occurs
     */
    fun sendRequest(request: ACPRequest, timeoutMs: Long = 30000): ACPResponse

    /**
     * Send a JSON-RPC notification (no response expected)
     *
     * @param notification The notification to send
     */
    fun sendNotification(notification: ACPNotification)

    /**
     * Register a handler for incoming notifications
     *
     * @param method The method name to handle
     * @param handler The handler function
     */
    fun onNotification(method: String, handler: (ACPNotification) -> Unit)

    /**
     * Register a handler for incoming requests (client methods)
     *
     * @param method The method name to handle
     * @param handler The handler function that returns a response
     */
    fun onRequest(method: String, handler: (ACPRequest) -> ACPResponse)

    /**
     * Check if transport is connected and ready
     */
    fun isConnected(): Boolean

    /**
     * Start receiving messages (begins the read loop)
     */
    fun startReceiving()

    /**
     * Stop receiving messages
     */
    fun stopReceiving()
}

/**
 * STDIO-based transport implementation for ACP
 *
 * Communicates with ACP agent subprocess via stdin/stdout pipes.
 * Messages are newline-delimited JSON (JSON-RPC 2.0).
 */
class StdioTransport(
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) : ACPTransport {

    private val log = Logger.getInstance(StdioTransport::class.java)

    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .create()

    private val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))

    private val requestIdCounter = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Any, PendingRequest>()
    private val notificationHandlers = ConcurrentHashMap<String, (ACPNotification) -> Unit>()
    private val requestHandlers = ConcurrentHashMap<String, (ACPRequest) -> ACPResponse>()

    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(true)

    @Volatile
    private var receiveThread: Thread? = null

    private val writeLock = Any()

    /**
     * Pending request with latch for synchronous waiting
     */
    private data class PendingRequest(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var response: ACPResponse? = null
    )

    override fun sendRequest(request: ACPRequest, timeoutMs: Long): ACPResponse {
        val pending = PendingRequest()
        pendingRequests[request.id] = pending

        try {
            sendMessage(gson.toJson(request))

            if (!pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pendingRequests.remove(request.id)
                throw TimeoutException("Request ${request.method} timed out after ${timeoutMs}ms")
            }

            return pending.response ?: throw IOException("No response received for request ${request.id}")
        } catch (e: InterruptedException) {
            pendingRequests.remove(request.id)
            Thread.currentThread().interrupt()
            throw IOException("Request interrupted", e)
        }
    }

    override fun sendNotification(notification: ACPNotification) {
        sendMessage(gson.toJson(notification))
    }

    override fun onNotification(method: String, handler: (ACPNotification) -> Unit) {
        notificationHandlers[method] = handler
    }

    override fun onRequest(method: String, handler: (ACPRequest) -> ACPResponse) {
        requestHandlers[method] = handler
    }

    override fun isConnected(): Boolean = isConnected.get() && isRunning.get()

    override fun startReceiving() {
        if (isRunning.getAndSet(true)) {
            log.warn("Receive loop already running")
            return
        }

        receiveThread = Thread({
            runReceiveLoop()
        }, "ACP-StdioTransport-Receiver").apply {
            isDaemon = true
            start()
        }

        log.info("STDIO transport started")
    }

    override fun stopReceiving() {
        isRunning.set(false)
        isConnected.set(false)
        receiveThread?.interrupt()
        receiveThread = null
        log.info("STDIO transport stopped")
    }

    override fun close() {
        stopReceiving()

        // Fail all pending requests
        pendingRequests.forEach { (id, pending) ->
            pending.response = ACPResponse(
                id = id,
                error = ACPError(
                    code = ACPError.INTERNAL_ERROR,
                    message = "Transport closed"
                )
            )
            pending.latch.countDown()
        }
        pendingRequests.clear()

        try {
            writer.close()
        } catch (e: IOException) {
            log.debug("Error closing writer", e)
        }

        try {
            reader.close()
        } catch (e: IOException) {
            log.debug("Error closing reader", e)
        }
    }

    /**
     * Send a message through the transport
     */
    private fun sendMessage(json: String) {
        synchronized(writeLock) {
            try {
                writer.write(json)
                writer.newLine()
                writer.flush()
                log.debug("Sent: $json")
            } catch (e: IOException) {
                isConnected.set(false)
                throw e
            }
        }
    }

    /**
     * Main receive loop - reads messages from stdin
     */
    private fun runReceiveLoop() {
        try {
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                val line = reader.readLine()

                if (line == null) {
                    // EOF - process terminated
                    log.info("EOF received, agent process terminated")
                    isConnected.set(false)
                    break
                }

                if (line.isBlank()) {
                    continue
                }

                // ACP keepalive comments start with ':'
                if (line.startsWith(":")) {
                    log.debug("Keepalive: $line")
                    continue
                }

                try {
                    processMessage(line)
                } catch (e: Exception) {
                    log.error("Error processing message: $line", e)
                }
            }
        } catch (e: IOException) {
            if (isRunning.get()) {
                log.error("IO error in receive loop", e)
                isConnected.set(false)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            isRunning.set(false)
            isConnected.set(false)

            // Wake up any waiting requests
            pendingRequests.forEach { (_, pending) ->
                pending.latch.countDown()
            }
        }
    }

    /**
     * Process a received JSON message
     */
    private fun processMessage(json: String) {
        log.debug("Received: $json")

        val element = JsonParser.parseString(json)
        if (!element.isJsonObject) {
            log.warn("Received non-object JSON: $json")
            return
        }

        val obj = element.asJsonObject

        when {
            // Response to a request we sent (has id and result/error)
            obj.has("id") && (obj.has("result") || obj.has("error")) -> {
                handleResponse(obj)
            }
            // Request from agent (has id and method)
            obj.has("id") && obj.has("method") -> {
                handleIncomingRequest(obj)
            }
            // Notification from agent (has method but no id)
            obj.has("method") && !obj.has("id") -> {
                handleNotification(obj)
            }
            else -> {
                log.warn("Unknown message format: $json")
            }
        }
    }

    /**
     * Handle a response to a request we sent
     */
    private fun handleResponse(obj: JsonObject) {
        val id = extractId(obj["id"])
        val pending = pendingRequests.remove(id)

        if (pending == null) {
            log.warn("Received response for unknown request id: $id")
            return
        }

        pending.response = ACPResponse(
            id = id,
            result = obj["result"],
            error = obj["error"]?.let { gson.fromJson(it, ACPError::class.java) }
        )
        pending.latch.countDown()
    }

    /**
     * Handle an incoming request from the agent
     */
    private fun handleIncomingRequest(obj: JsonObject) {
        val method = obj["method"].asString
        val id = extractId(obj["id"])
        val params = obj["params"]?.asJsonObject

        val request = ACPRequest(
            id = id,
            method = method,
            params = params
        )

        val handler = requestHandlers[method]
        val response = if (handler != null) {
            try {
                handler(request)
            } catch (e: Exception) {
                log.error("Error handling request $method", e)
                ACPResponse(
                    id = id,
                    error = ACPError(
                        code = ACPError.INTERNAL_ERROR,
                        message = e.message ?: "Unknown error"
                    )
                )
            }
        } else {
            log.warn("No handler for method: $method")
            ACPResponse(
                id = id,
                error = ACPError(
                    code = ACPError.METHOD_NOT_FOUND,
                    message = "Method not found: $method"
                )
            )
        }

        sendMessage(gson.toJson(response))
    }

    /**
     * Handle an incoming notification from the agent
     */
    private fun handleNotification(obj: JsonObject) {
        val method = obj["method"].asString
        val params = obj["params"]?.asJsonObject

        val notification = ACPNotification(
            method = method,
            params = params
        )

        val handler = notificationHandlers[method]
        if (handler != null) {
            try {
                handler(notification)
            } catch (e: Exception) {
                log.error("Error handling notification $method", e)
            }
        } else {
            log.debug("No handler for notification: $method")
        }
    }

    /**
     * Extract id from JSON element (can be string or number)
     */
    private fun extractId(element: JsonElement?): Any {
        return when {
            element == null -> 0
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isNumber -> primitive.asLong
                    primitive.isString -> primitive.asString
                    else -> element.toString()
                }
            }
            else -> element.toString()
        }
    }

    /**
     * Generate a unique request ID
     */
    fun nextRequestId(): Long = requestIdCounter.getAndIncrement()

    companion object {
        /**
         * Create a StdioTransport from a Process
         */
        fun fromProcess(process: Process): StdioTransport {
            return StdioTransport(
                inputStream = process.inputStream,
                outputStream = process.outputStream
            )
        }
    }
}

/**
 * Transport state for monitoring
 */
enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Transport event listener interface
 */
interface ACPTransportListener {
    fun onStateChanged(state: TransportState)
    fun onError(error: Throwable)
}
