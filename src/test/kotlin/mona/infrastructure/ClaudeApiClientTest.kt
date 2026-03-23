package mona.infrastructure

import kotlinx.coroutines.test.runTest
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.UserId
import mona.domain.port.ConversationMessage
import mona.domain.port.LlmResponse
import mona.domain.port.LlmToolDefinition
import mona.domain.port.MessageRole
import mona.infrastructure.llm.ClaudeApiClient
import mona.infrastructure.llm.ClaudeHttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeApiClientTest {
    private fun client(handler: suspend (String, String, String) -> ClaudeHttpResponse): ClaudeApiClient =
        ClaudeApiClient(
            apiKey = "test-api-key",
            httpExecutor = { url, apiKey, body -> handler(url, apiKey, body) },
        )

    private fun message(
        role: MessageRole,
        content: String,
    ) = ConversationMessage(
        id = UUID.randomUUID().toString(),
        userId = UserId("user-1"),
        role = role,
        content = content,
        createdAt = Instant.now(),
    )

    @Test
    fun `returns Text response on text content`() =
        runTest {
            val adapter =
                client { _, _, _ ->
                    ClaudeHttpResponse(
                        200,
                        """{"content":[{"type":"text","text":"Bonjour!"}],"stop_reason":"end_turn"}""",
                    )
                }
            val result = adapter.complete("system", "", listOf(message(MessageRole.USER, "hello")), emptyList())
            assertIs<DomainResult.Ok<LlmResponse>>(result)
            val response = result.value
            assertIs<LlmResponse.Text>(response)
            assertEquals("Bonjour!", response.text)
        }

    @Test
    fun `returns ToolUse response on tool_use content`() =
        runTest {
            val adapter =
                client { _, _, _ ->
                    ClaudeHttpResponse(
                        200,
                        // language=JSON
                        """{"content":[{"type":"tool_use","id":"toolu_01","name":"create_invoice",""" +
                            """"input":{"client_name":"Jean"}}],"stop_reason":"tool_use"}""",
                    )
                }
            val result =
                adapter.complete("system", "", listOf(message(MessageRole.USER, "facture pour Jean")), emptyList())
            assertIs<DomainResult.Ok<LlmResponse>>(result)
            val response = result.value
            assertIs<LlmResponse.ToolUse>(response)
            assertEquals("create_invoice", response.toolName)
            assertEquals("toolu_01", response.toolUseId)
            assertTrue(response.inputJson.contains("Jean"))
        }

    @Test
    fun `prefers tool_use over text when both present`() =
        runTest {
            val adapter =
                client { _, _, _ ->
                    ClaudeHttpResponse(
                        200,
                        """{"content":[{"type":"text","text":"thinking..."},""" +
                            """{"type":"tool_use","id":"toolu_02","name":"send_invoice",""" +
                            """"input":{"invoice_id":"F-2024-01-001"}}]}""",
                    )
                }
            val result = adapter.complete("system", "", listOf(message(MessageRole.USER, "envoie")), emptyList())
            assertIs<DomainResult.Ok<LlmResponse>>(result)
            assertIs<LlmResponse.ToolUse>(result.value)
        }

    @Test
    fun `returns Err on HTTP 500`() =
        runTest {
            val adapter =
                client { _, _, _ ->
                    ClaudeHttpResponse(500, """{"error":"internal server error"}""")
                }
            val result = adapter.complete("system", "", listOf(message(MessageRole.USER, "hello")), emptyList())
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.LlmUnavailable>(result.error)
            assertTrue(result.error.message.contains("500"))
        }

    @Test
    fun `returns Err on HTTP 401`() =
        runTest {
            val adapter =
                client { _, _, _ ->
                    ClaudeHttpResponse(401, """{"error":"unauthorized"}""")
                }
            val result = adapter.complete("system", "", listOf(message(MessageRole.USER, "hello")), emptyList())
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.LlmUnavailable>(result.error)
            assertTrue(result.error.message.contains("401"))
        }

    @Test
    fun `returns Err on malformed JSON response`() =
        runTest {
            val adapter =
                client { _, _, _ ->
                    ClaudeHttpResponse(200, "not json at all")
                }
            val result = adapter.complete("system", "", listOf(message(MessageRole.USER, "hello")), emptyList())
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.LlmUnavailable>(result.error)
        }

    @Test
    fun `sends correct API key`() =
        runTest {
            var capturedApiKey = ""
            val adapter =
                ClaudeApiClient(
                    apiKey = "my-secret-key",
                    httpExecutor = { _, apiKey, _ ->
                        capturedApiKey = apiKey
                        ClaudeHttpResponse(
                            200,
                            """{"content":[{"type":"text","text":"ok"}]}""",
                        )
                    },
                )
            adapter.complete("system", "", listOf(message(MessageRole.USER, "hello")), emptyList())
            assertEquals("my-secret-key", capturedApiKey)
        }

    @Test
    fun `combines systemPrompt and userContextJson in system field`() =
        runTest {
            var capturedBody = ""
            val adapter =
                client { _, _, body ->
                    capturedBody = body
                    ClaudeHttpResponse(200, """{"content":[{"type":"text","text":"ok"}]}""")
                }
            adapter.complete(
                "Tu es Mona",
                """{"name":"Alice"}""",
                listOf(message(MessageRole.USER, "hello")),
                emptyList(),
            )
            assertTrue(capturedBody.contains("Tu es Mona"))
            assertTrue(capturedBody.contains("Alice"))
        }

    @Test
    fun `omits system context when userContextJson is blank`() =
        runTest {
            var capturedBody = ""
            val adapter =
                client { _, _, body ->
                    capturedBody = body
                    ClaudeHttpResponse(200, """{"content":[{"type":"text","text":"ok"}]}""")
                }
            adapter.complete("Tu es Mona", "", listOf(message(MessageRole.USER, "hello")), emptyList())
            assertTrue(capturedBody.contains("Tu es Mona"))
        }

    @Test
    fun `sends tool definitions in request`() =
        runTest {
            var capturedBody = ""
            val adapter =
                client { _, _, body ->
                    capturedBody = body
                    ClaudeHttpResponse(200, """{"content":[{"type":"text","text":"ok"}]}""")
                }
            val tool =
                LlmToolDefinition(
                    name = "create_invoice",
                    description = "Creates an invoice",
                    inputSchemaJson = """{"type":"object","properties":{"client_name":{"type":"string"}}}""",
                )
            adapter.complete("system", "", listOf(message(MessageRole.USER, "test")), listOf(tool))
            assertTrue(capturedBody.contains("create_invoice"))
            assertTrue(capturedBody.contains("Creates an invoice"))
            assertTrue(capturedBody.contains("input_schema"))
        }

    @Test
    fun `sends messages with correct roles`() =
        runTest {
            var capturedBody = ""
            val adapter =
                client { _, _, body ->
                    capturedBody = body
                    ClaudeHttpResponse(200, """{"content":[{"type":"text","text":"ok"}]}""")
                }
            adapter.complete(
                "system",
                "",
                listOf(
                    message(MessageRole.USER, "hello"),
                    message(MessageRole.ASSISTANT, "hi there"),
                ),
                emptyList(),
            )
            assertTrue(capturedBody.contains("\"user\""))
            assertTrue(capturedBody.contains("\"assistant\""))
            assertTrue(capturedBody.contains("hello"))
            assertTrue(capturedBody.contains("hi there"))
        }
}
