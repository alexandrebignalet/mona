package mona.infrastructure

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mona.domain.model.InvoiceNumber
import mona.infrastructure.email.BounceProcessor
import mona.infrastructure.email.ResendWebhookHandler
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TEST_SECRET = "whsec_dGVzdHNlY3JldA=="
private const val SVIX_ID = "msg_123"
private const val SVIX_TIMESTAMP = "1700000000"

private fun computeSignature(
    svixId: String,
    svixTimestamp: String,
    body: String,
): String {
    val secretBytes = Base64.getDecoder().decode(TEST_SECRET.removePrefix("whsec_"))
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
    val signed = "$svixId.$svixTimestamp.$body"
    return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(signed.toByteArray(Charsets.UTF_8)))
}

private fun bouncePayload(
    subject: String = "Facture F-2026-03-001",
    to: String = "client@example.com",
): String = """{"type":"email.bounced","data":{"email_id":"abc","from":"factures@mona-app.fr","to":["$to"],"subject":"$subject"}}"""

class ResendWebhookHandlerTest {
    private lateinit var server: HttpServer
    private lateinit var scope: CoroutineScope
    private val calls = mutableListOf<Pair<InvoiceNumber, String>>()
    private val httpClient = HttpClient.newHttpClient()
    private var port = 0

    @BeforeTest
    fun setUp() {
        calls.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processor = BounceProcessor { number, email -> calls += Pair(number, email) }
        val handler = ResendWebhookHandler(TEST_SECRET, processor, scope)

        server = HttpServer.create(InetSocketAddress(0), 0)
        port = server.address.port
        server.createContext("/webhook/resend") { exchange ->
            if (exchange.requestMethod == "POST") {
                handler.handle(exchange)
            } else {
                exchange.sendResponseHeaders(405, 0)
                exchange.responseBody.close()
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        scope.cancel()
    }

    private fun post(
        body: String,
        svixId: String = SVIX_ID,
        svixTimestamp: String = SVIX_TIMESTAMP,
        signature: String = computeSignature(svixId, svixTimestamp, body),
    ): Int {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/webhook/resend"))
                .header("Content-Type", "application/json")
                .header("svix-id", svixId)
                .header("svix-timestamp", svixTimestamp)
                .header("svix-signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return httpClient.send(request, BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `valid bounce event returns 200 and triggers processor`() =
        runBlocking {
            val body = bouncePayload()
            val status = post(body)
            assertEquals(200, status)
            Thread.sleep(200) // allow scope.launch to run
            assertEquals(1, calls.size)
            assertEquals(InvoiceNumber("F-2026-03-001"), calls[0].first)
            assertEquals("client@example.com", calls[0].second)
        }

    @Test
    fun `invalid signature returns 400 and does not trigger processor`() =
        runBlocking {
            val body = bouncePayload()
            val status = post(body, signature = "v1,invalidsignature")
            assertEquals(400, status)
            Thread.sleep(100)
            assertEquals(0, calls.size)
        }

    @Test
    fun `non-bounce event type returns 200 but does not trigger processor`() =
        runBlocking {
            val body = """{"type":"email.delivered","data":{}}"""
            val status = post(body)
            assertEquals(200, status)
            Thread.sleep(100)
            assertEquals(0, calls.size)
        }

    @Test
    fun `malformed JSON returns 400`() =
        runBlocking {
            val status = post("not valid json")
            assertEquals(400, status)
        }

    @Test
    fun `subject without invoice number returns 200 but does not trigger processor`() =
        runBlocking {
            val body = bouncePayload(subject = "Bonjour")
            val status = post(body)
            assertEquals(200, status)
            Thread.sleep(100)
            assertEquals(0, calls.size)
        }

    @Test
    fun `empty signing secret skips signature check and processes event`() =
        runBlocking {
            // Create handler with empty secret — should accept any request
            val emptyCalls = mutableListOf<Pair<InvoiceNumber, String>>()
            val processor = BounceProcessor { n, e -> emptyCalls += Pair(n, e) }
            val handler = ResendWebhookHandler("", processor, scope)

            val emptyServer = HttpServer.create(InetSocketAddress(0), 0)
            val emptyPort = emptyServer.address.port
            emptyServer.createContext("/webhook/resend") { exchange ->
                handler.handle(exchange)
            }
            emptyServer.start()

            try {
                val body = bouncePayload()
                val request =
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:$emptyPort/webhook/resend"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                val status = httpClient.send(request, BodyHandlers.discarding()).statusCode()
                assertEquals(200, status)
                Thread.sleep(200)
                assertEquals(1, emptyCalls.size)
            } finally {
                emptyServer.stop(0)
            }
        }

    @Test
    fun `extracts invoice number and recipient from bounce event correctly`() =
        runBlocking {
            val body = bouncePayload(subject = "Facture F-2025-12-042", to = "jean.dupont@gmail.com")
            post(body)
            Thread.sleep(200)
            assertTrue(calls.isNotEmpty())
            assertEquals(InvoiceNumber("F-2025-12-042"), calls[0].first)
            assertEquals("jean.dupont@gmail.com", calls[0].second)
        }
}
