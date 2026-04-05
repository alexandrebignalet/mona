package mona.infrastructure

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mona.domain.model.ActivityType
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.PaymentDelayDays
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.Button
import mona.domain.port.IncomingCallback
import mona.domain.port.IncomingMessage
import mona.domain.port.MenuItem
import mona.domain.port.UserRepository
import mona.infrastructure.telegram.MultipartField
import mona.infrastructure.telegram.TelegramApiClient
import mona.infrastructure.telegram.TelegramBotAdapter
import mona.infrastructure.telegram.TelegramHttpExecutor
import mona.infrastructure.telegram.TelegramHttpResponse
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

private const val WEBHOOK_SECRET = "test-secret"
private const val WEBHOOK_URL = "https://example.com/webhook/telegram"

private class RecordingExecutor : TelegramHttpExecutor {
    var lastMethod: String? = null
    var lastBodyJson: String? = null
    var lastParts: List<MultipartField>? = null
    var responseBody: String = """{"ok":true,"result":true}"""

    override suspend fun post(
        method: String,
        bodyJson: String,
    ): TelegramHttpResponse {
        lastMethod = method
        lastBodyJson = bodyJson
        return TelegramHttpResponse(200, responseBody)
    }

    override suspend fun postMultipart(
        method: String,
        parts: List<MultipartField>,
    ): TelegramHttpResponse {
        lastMethod = method
        lastParts = parts
        return TelegramHttpResponse(200, responseBody)
    }
}

private fun fakeClient(executor: RecordingExecutor = RecordingExecutor()): TelegramApiClient =
    TelegramApiClient(token = "test-token", executor = executor)

private class FakeUserRepository(private val user: User? = null) : UserRepository {
    override suspend fun findById(id: UserId): User? = user?.takeIf { it.id == id }

    override suspend fun findByTelegramId(telegramId: Long): User? = user?.takeIf { it.telegramId == telegramId }

    override suspend fun save(user: User) {}

    override suspend fun delete(userId: UserId) {}

    override suspend fun findAllWithPeriodicity(): List<User> = emptyList()

    override suspend fun findAllWithoutSiren(): List<User> = emptyList()
}

private fun fakeUser(telegramId: Long = 100L): User =
    User(
        id = UserId("user-1"),
        telegramId = telegramId,
        email = null,
        name = null,
        siren = null,
        siret = null,
        address = null,
        ibanEncrypted = null,
        activityType = ActivityType.BIC_SERVICE,
        declarationPeriodicity = DeclarationPeriodicity.MONTHLY,
        confirmBeforeCreate = false,
        defaultPaymentDelayDays = PaymentDelayDays(30),
        createdAt = Instant.EPOCH,
    )

private class FakeHttpExchange(
    private val method: String = "POST",
    headerMap: Map<String, String> = emptyMap(),
    private val bodyContent: String = "",
) : HttpExchange() {
    private val fakeRequestHeaders = Headers().also { headers -> headerMap.forEach { (k, v) -> headers.add(k, v) } }
    var capturedResponseCode: Int = -1
    val responseBodyStream: ByteArrayOutputStream = ByteArrayOutputStream()

    override fun getRequestHeaders(): Headers = fakeRequestHeaders

    override fun getResponseHeaders(): Headers = Headers()

    override fun getRequestURI(): URI = URI.create("/webhook/telegram")

    override fun getRequestMethod(): String = method

    override fun getHttpContext(): HttpContext = throw UnsupportedOperationException()

    override fun close() {}

    override fun getRequestBody(): InputStream = bodyContent.byteInputStream()

    override fun sendResponseHeaders(
        rCode: Int,
        responseLength: Long,
    ) {
        capturedResponseCode = rCode
    }

    override fun getResponseBody(): OutputStream = responseBodyStream

    override fun getRemoteAddress(): InetSocketAddress = InetSocketAddress("127.0.0.1", 12345)

    override fun getResponseCode(): Int = capturedResponseCode

    override fun getLocalAddress(): InetSocketAddress = InetSocketAddress("127.0.0.1", 8080)

    override fun getProtocol(): String = "HTTP/1.1"

    override fun getAttribute(name: String): Any? = null

    override fun setAttribute(
        name: String,
        value: Any?,
    ) {}

    override fun setStreams(
        i: InputStream?,
        o: OutputStream?,
    ) {}

    override fun getPrincipal(): HttpPrincipal? = null
}

// ---------------------------------------------------------------------------
// Webhook handler tests
// ---------------------------------------------------------------------------

class TelegramBotAdapterTest {
    @Test
    fun `valid message update dispatches IncomingMessage to registered handler`() =
        runTest {
            val dispatched = CompletableDeferred<IncomingMessage>()
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(),
                    userRepository = FakeUserRepository(),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.onMessage { dispatched.complete(it) }

            val exchange =
                FakeHttpExchange(
                    headerMap = mapOf("X-Telegram-Bot-Api-Secret-Token" to WEBHOOK_SECRET),
                    bodyContent =
                        """{"update_id":1,"message":{"message_id":1,"chat":{"id":100},"text":"hello"}}""",
                )
            adapter.handleWebhook(exchange)

            val message = withTimeout(1000) { dispatched.await() }
            assertEquals(100L, message.telegramId)
            assertEquals("hello", message.text)
            assertNull(message.userId)
            assertEquals(200, exchange.capturedResponseCode)
        }

    @Test
    fun `message update resolves userId from user repository`() =
        runTest {
            val user = fakeUser(telegramId = 100L)
            val dispatched = CompletableDeferred<IncomingMessage>()
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(),
                    userRepository = FakeUserRepository(user),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.onMessage { dispatched.complete(it) }

            adapter.handleWebhook(
                FakeHttpExchange(
                    headerMap = mapOf("X-Telegram-Bot-Api-Secret-Token" to WEBHOOK_SECRET),
                    bodyContent =
                        """{"update_id":1,"message":{"message_id":1,"chat":{"id":100},"text":"hi"}}""",
                ),
            )

            val message = withTimeout(1000) { dispatched.await() }
            assertEquals(UserId("user-1"), message.userId)
        }

    @Test
    fun `valid callback_query update dispatches IncomingCallback to registered handler`() =
        runTest {
            val dispatched = CompletableDeferred<IncomingCallback>()
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(),
                    userRepository = FakeUserRepository(),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.onCallback { dispatched.complete(it) }

            adapter.handleWebhook(
                FakeHttpExchange(
                    headerMap = mapOf("X-Telegram-Bot-Api-Secret-Token" to WEBHOOK_SECRET),
                    bodyContent =
                        """{"update_id":2,"callback_query":{"id":"cq-1","from":{"id":200},"data":"action:pay"}}""",
                ),
            )

            val cb = withTimeout(1000) { dispatched.await() }
            assertEquals(200L, cb.telegramId)
            assertEquals("cq-1", cb.callbackQueryId)
            assertEquals("action:pay", cb.data)
        }

    @Test
    fun `missing secret token returns 401 and does not dispatch`() =
        runTest {
            var dispatched = false
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(),
                    userRepository = FakeUserRepository(),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.onMessage { dispatched = true }

            val exchange =
                FakeHttpExchange(
                    headerMap = emptyMap(),
                    bodyContent =
                        """{"update_id":1,"message":{"message_id":1,"chat":{"id":100},"text":"hi"}}""",
                )
            adapter.handleWebhook(exchange)

            assertEquals(401, exchange.capturedResponseCode)
            assertEquals(false, dispatched)
        }

    @Test
    fun `wrong secret token returns 401 and does not dispatch`() =
        runTest {
            var dispatched = false
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(),
                    userRepository = FakeUserRepository(),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.onMessage { dispatched = true }

            val exchange =
                FakeHttpExchange(
                    headerMap = mapOf("X-Telegram-Bot-Api-Secret-Token" to "wrong-secret"),
                    bodyContent =
                        """{"update_id":1,"message":{"message_id":1,"chat":{"id":100},"text":"hi"}}""",
                )
            adapter.handleWebhook(exchange)

            assertEquals(401, exchange.capturedResponseCode)
            assertEquals(false, dispatched)
        }

    @Test
    fun `malformed JSON returns 200 and does not dispatch`() =
        runTest {
            var dispatched = false
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(),
                    userRepository = FakeUserRepository(),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.onMessage { dispatched = true }

            val exchange =
                FakeHttpExchange(
                    headerMap = mapOf("X-Telegram-Bot-Api-Secret-Token" to WEBHOOK_SECRET),
                    bodyContent = "not valid json {{{",
                )
            adapter.handleWebhook(exchange)

            assertEquals(200, exchange.capturedResponseCode)
            assertEquals(false, dispatched)
        }

    @Test
    fun `message with null text does not dispatch`() =
        runTest {
            var dispatched = false
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(),
                    userRepository = FakeUserRepository(),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.onMessage { dispatched = true }

            val exchange =
                FakeHttpExchange(
                    headerMap = mapOf("X-Telegram-Bot-Api-Secret-Token" to WEBHOOK_SECRET),
                    bodyContent =
                        """{"update_id":1,"message":{"message_id":1,"chat":{"id":100}}}""",
                )
            adapter.handleWebhook(exchange)

            assertEquals(200, exchange.capturedResponseCode)
            assertEquals(false, dispatched)
        }

    // ---------------------------------------------------------------------------
    // Outbound method tests
    // ---------------------------------------------------------------------------

    @Test
    fun `sendMessage delegates to apiClient with resolved chatId`() =
        runTest {
            val user = fakeUser(telegramId = 42L)
            val executor =
                RecordingExecutor().apply {
                    responseBody = """{"ok":true,"result":{"message_id":1,"chat":{"id":42},"text":"hi"}}"""
                }
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(executor),
                    userRepository = FakeUserRepository(user),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.sendMessage(user.id, "hello bot")

            assertEquals("sendMessage", executor.lastMethod)
            val body = Json.parseToJsonElement(executor.lastBodyJson!!).jsonObject
            assertEquals(42L, body["chat_id"]!!.jsonPrimitive.content.toLong())
            assertEquals("hello bot", body["text"]!!.jsonPrimitive.content)
        }

    @Test
    fun `sendButtons builds inline keyboard JSON with button text and callbackData`() =
        runTest {
            val user = fakeUser(telegramId = 42L)
            val executor =
                RecordingExecutor().apply {
                    responseBody = """{"ok":true,"result":{"message_id":1,"chat":{"id":42}}}"""
                }
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(executor),
                    userRepository = FakeUserRepository(user),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.sendButtons(
                userId = user.id,
                text = "Choose:",
                buttons = listOf(Button("Pay", "pay:1"), Button("Cancel", "cancel:1")),
            )

            val body = Json.parseToJsonElement(executor.lastBodyJson!!).jsonObject
            val markup = body["reply_markup"]!!.jsonObject
            val row = markup["inline_keyboard"]!!.jsonArray[0].jsonArray
            assertEquals(2, row.size)
            assertEquals("Pay", row[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("pay:1", row[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
            assertEquals("Cancel", row[1].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("cancel:1", row[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
        }

    @Test
    fun `sendDocument delegates to apiClient sendDocument`() =
        runTest {
            val user = fakeUser(telegramId = 7L)
            val executor =
                RecordingExecutor().apply {
                    responseBody = """{"ok":true,"result":{"message_id":2,"chat":{"id":7}}}"""
                }
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(executor),
                    userRepository = FakeUserRepository(user),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            val bytes = byteArrayOf(1, 2, 3)
            adapter.sendDocument(user.id, bytes, "invoice.pdf", "Your invoice")

            assertEquals("sendDocument", executor.lastMethod)
            val chatIdPart =
                executor.lastParts!!.filterIsInstance<MultipartField.Text>().first { it.name == "chat_id" }
            assertEquals("7", chatIdPart.value)
        }

    @Test
    fun `answerCallback delegates to apiClient answerCallbackQuery`() =
        runTest {
            val executor = RecordingExecutor()
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(executor),
                    userRepository = FakeUserRepository(),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.answerCallback("cq-99", "Done!")

            assertEquals("answerCallbackQuery", executor.lastMethod)
            val body = Json.parseToJsonElement(executor.lastBodyJson!!).jsonObject
            assertEquals("cq-99", body["callback_query_id"]!!.jsonPrimitive.content)
            assertEquals("Done!", body["text"]!!.jsonPrimitive.content)
        }

    // ---------------------------------------------------------------------------
    // Lifecycle tests
    // ---------------------------------------------------------------------------

    @Test
    fun `start calls setWebhook with correct url and secret`() =
        runTest {
            val executor = RecordingExecutor()
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(executor),
                    userRepository = FakeUserRepository(),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.start()

            assertEquals("setWebhook", executor.lastMethod)
            val body = Json.parseToJsonElement(executor.lastBodyJson!!).jsonObject
            assertEquals(WEBHOOK_URL, body["url"]!!.jsonPrimitive.content)
            assertEquals(WEBHOOK_SECRET, body["secret_token"]!!.jsonPrimitive.content)
            val updates = body["allowed_updates"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("message", "callback_query"), updates)
        }

    @Test
    fun `stop calls deleteWebhook`() =
        runTest {
            val executor = RecordingExecutor()
            val adapterScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(executor),
                    userRepository = FakeUserRepository(),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = adapterScope,
                )
            adapter.stop()

            assertEquals("deleteWebhook", executor.lastMethod)
        }

    @Test
    fun `setPersistentMenu attaches reply keyboard to subsequent sendMessage`() =
        runTest {
            val user = fakeUser(telegramId = 5L)
            val executor =
                RecordingExecutor().apply {
                    responseBody = """{"ok":true,"result":{"message_id":1,"chat":{"id":5}}}"""
                }
            val adapter =
                TelegramBotAdapter(
                    apiClient = fakeClient(executor),
                    userRepository = FakeUserRepository(user),
                    webhookUrl = WEBHOOK_URL,
                    webhookSecret = WEBHOOK_SECRET,
                    scope = this,
                )
            adapter.setPersistentMenu(user.id, listOf(MenuItem("Nouvelle facture"), MenuItem("Mon CA")))
            adapter.sendMessage(user.id, "Bonjour!")

            val body = Json.parseToJsonElement(executor.lastBodyJson!!).jsonObject
            val markup = body["reply_markup"]!!.jsonObject
            val row = markup["keyboard"]!!.jsonArray[0].jsonArray
            assertEquals(2, row.size)
            assertEquals("Nouvelle facture", row[0].jsonPrimitive.content)
        }
}
