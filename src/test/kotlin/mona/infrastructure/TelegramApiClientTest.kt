package mona.infrastructure

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mona.infrastructure.telegram.MultipartField
import mona.infrastructure.telegram.TelegramApiClient
import mona.infrastructure.telegram.TelegramHttpExecutor
import mona.infrastructure.telegram.TelegramHttpResponse
import mona.infrastructure.telegram.TgResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramApiClientTest {
    private var capturedMethod: String? = null
    private var capturedBodyJson: String? = null
    private var capturedParts: List<MultipartField>? = null

    private fun clientWith(responseBody: String): TelegramApiClient =
        TelegramApiClient(
            token = "test-token",
            executor =
                object : TelegramHttpExecutor {
                    override suspend fun post(
                        method: String,
                        bodyJson: String,
                    ): TelegramHttpResponse {
                        capturedMethod = method
                        capturedBodyJson = bodyJson
                        return TelegramHttpResponse(200, responseBody)
                    }

                    override suspend fun postMultipart(
                        method: String,
                        parts: List<MultipartField>,
                    ): TelegramHttpResponse {
                        capturedMethod = method
                        capturedParts = parts
                        return TelegramHttpResponse(200, responseBody)
                    }
                },
        )

    // -- sendMessage --

    @Test
    fun `sendMessage builds correct JSON payload`() =
        runTest {
            val client =
                clientWith(
                    """{"ok":true,"result":{"message_id":1,"chat":{"id":42},"text":"hi"}}""",
                )
            val result = client.sendMessage(chatId = 42L, text = "hello")
            assertIs<TgResult.Ok<*>>(result)
            assertEquals("sendMessage", capturedMethod)
            val body = Json.parseToJsonElement(capturedBodyJson!!).jsonObject
            assertEquals(42L, body["chat_id"]!!.jsonPrimitive.long)
            assertEquals("hello", body["text"]!!.jsonPrimitive.content)
            assertEquals("Markdown", body["parse_mode"]!!.jsonPrimitive.content)
        }

    @Test
    fun `sendMessage includes reply_markup when provided`() =
        runTest {
            val markup =
                buildJsonObject {
                    put("inline_keyboard", buildJsonArray {})
                }
            val client =
                clientWith(
                    """{"ok":true,"result":{"message_id":1,"chat":{"id":42}}}""",
                )
            client.sendMessage(chatId = 42L, text = "pick one", replyMarkup = markup)
            val body = Json.parseToJsonElement(capturedBodyJson!!).jsonObject
            assertTrue(body.containsKey("reply_markup"))
        }

    @Test
    fun `sendMessage omits reply_markup when null`() =
        runTest {
            val client =
                clientWith(
                    """{"ok":true,"result":{"message_id":1,"chat":{"id":1}}}""",
                )
            client.sendMessage(chatId = 1L, text = "no buttons")
            val body = Json.parseToJsonElement(capturedBodyJson!!).jsonObject
            assertTrue(!body.containsKey("reply_markup"))
        }

    // -- sendDocument --

    @Test
    fun `sendDocument builds correct multipart body with file bytes and caption`() =
        runTest {
            val fileBytes = byteArrayOf(1, 2, 3, 4)
            val client =
                clientWith(
                    """{"ok":true,"result":{"message_id":2,"chat":{"id":7}}}""",
                )
            val result =
                client.sendDocument(
                    chatId = 7L,
                    fileBytes = fileBytes,
                    fileName = "invoice.pdf",
                    caption = "Here is your invoice",
                )
            assertIs<TgResult.Ok<*>>(result)
            assertEquals("sendDocument", capturedMethod)
            val parts = capturedParts!!
            val chatIdPart = parts.filterIsInstance<MultipartField.Text>().first { it.name == "chat_id" }
            assertEquals("7", chatIdPart.value)
            val captionPart = parts.filterIsInstance<MultipartField.Text>().first { it.name == "caption" }
            assertEquals("Here is your invoice", captionPart.value)
            val filePart = parts.filterIsInstance<MultipartField.File>().first { it.name == "document" }
            assertEquals("invoice.pdf", filePart.fileName)
            assertTrue(fileBytes.contentEquals(filePart.bytes))
        }

    @Test
    fun `sendDocument omits caption field when null`() =
        runTest {
            val client =
                clientWith(
                    """{"ok":true,"result":{"message_id":2,"chat":{"id":7}}}""",
                )
            client.sendDocument(chatId = 7L, fileBytes = byteArrayOf(0), fileName = "doc.pdf", caption = null)
            val parts = capturedParts!!
            assertTrue(parts.filterIsInstance<MultipartField.Text>().none { it.name == "caption" })
        }

    // -- answerCallbackQuery --

    @Test
    fun `answerCallbackQuery sends callback_query_id and optional text`() =
        runTest {
            val client = clientWith("""{"ok":true,"result":true}""")
            val result = client.answerCallbackQuery("cq-123", "Done!")
            assertIs<TgResult.Ok<Boolean>>(result)
            assertTrue(result.value)
            assertEquals("answerCallbackQuery", capturedMethod)
            val body = Json.parseToJsonElement(capturedBodyJson!!).jsonObject
            assertEquals("cq-123", body["callback_query_id"]!!.jsonPrimitive.content)
            assertEquals("Done!", body["text"]!!.jsonPrimitive.content)
        }

    @Test
    fun `answerCallbackQuery omits text when null`() =
        runTest {
            val client = clientWith("""{"ok":true,"result":true}""")
            client.answerCallbackQuery("cq-456")
            val body = Json.parseToJsonElement(capturedBodyJson!!).jsonObject
            assertTrue(!body.containsKey("text"))
        }

    // -- setWebhook --

    @Test
    fun `setWebhook sends url, secret_token, and allowed_updates`() =
        runTest {
            val client = clientWith("""{"ok":true,"result":true}""")
            val result =
                client.setWebhook(
                    url = "https://example.com/webhook",
                    secretToken = "my-secret",
                    allowedUpdates = listOf("message", "callback_query"),
                )
            assertIs<TgResult.Ok<Boolean>>(result)
            assertEquals("setWebhook", capturedMethod)
            val body = Json.parseToJsonElement(capturedBodyJson!!).jsonObject
            assertEquals("https://example.com/webhook", body["url"]!!.jsonPrimitive.content)
            assertEquals("my-secret", body["secret_token"]!!.jsonPrimitive.content)
            val updates = body["allowed_updates"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("message", "callback_query"), updates)
        }

    // -- deleteWebhook --

    @Test
    fun `deleteWebhook sends POST to deleteWebhook method`() =
        runTest {
            val client = clientWith("""{"ok":true,"result":true}""")
            val result = client.deleteWebhook()
            assertIs<TgResult.Ok<Boolean>>(result)
            assertEquals("deleteWebhook", capturedMethod)
        }

    // -- TgResult parsing --

    @Test
    fun `success response parses to TgResult Ok`() =
        runTest {
            val client =
                clientWith(
                    """{"ok":true,"result":{"message_id":5,"chat":{"id":1},"text":"test"}}""",
                )
            val result = client.sendMessage(1L, "hi")
            assertIs<TgResult.Ok<*>>(result)
        }

    @Test
    fun `error response with ok=false parses to TgResult Err with code and description`() =
        runTest {
            val client =
                clientWith(
                    """{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""",
                )
            val result = client.sendMessage(999L, "hi")
            assertIs<TgResult.Err>(result)
            assertEquals(400, result.code)
            assertEquals("Bad Request: chat not found", result.description)
        }

    @Test
    fun `malformed response body parses to TgResult Err with parse error`() =
        runTest {
            val client = clientWith("not valid json{{{")
            val result = client.sendMessage(1L, "hi")
            assertIs<TgResult.Err>(result)
            assertTrue(result.description.startsWith("Parse error"))
        }
}
