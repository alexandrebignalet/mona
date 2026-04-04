package mona.infrastructure.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.port.ConversationMessage
import mona.domain.port.LlmPort
import mona.domain.port.LlmResponse
import mona.domain.port.LlmToolDefinition
import mona.domain.port.MessageRole
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

internal data class ClaudeHttpResponse(val statusCode: Int, val body: String)

internal fun interface ClaudeHttpExecutor {
    suspend fun post(
        url: String,
        apiKey: String,
        body: String,
    ): ClaudeHttpResponse
}

internal class RealClaudeHttpExecutor : ClaudeHttpExecutor {
    private val client: HttpClient = HttpClient.newHttpClient()

    override suspend fun post(
        url: String,
        apiKey: String,
        body: String,
    ): ClaudeHttpResponse =
        withContext(Dispatchers.IO) {
            val request =
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(BodyPublishers.ofString(body))
                    .build()
            val response = client.send(request, BodyHandlers.ofString())
            ClaudeHttpResponse(response.statusCode(), response.body())
        }
}

class ClaudeApiClient internal constructor(
    private val apiKey: String,
    private val httpExecutor: ClaudeHttpExecutor = RealClaudeHttpExecutor(),
    private val model: String = MODEL,
    private val baseUrl: String = BASE_URL,
) : LlmPort {
    companion object {
        const val BASE_URL = "https://api.anthropic.com/v1"
        const val MODEL = "claude-sonnet-4-6"

        private const val MAX_RETRIES = 4
        private const val RETRY_BASE_DELAY_MS = 2000L

        private val json = Json { ignoreUnknownKeys = true }

        fun fromEnv(): ClaudeApiClient =
            ClaudeApiClient(
                apiKey =
                    System.getenv("ANTHROPIC_API_KEY")
                        ?: error("ANTHROPIC_API_KEY environment variable is not set"),
            )
    }

    override suspend fun complete(
        systemPrompt: String,
        userContextJson: String,
        messages: List<ConversationMessage>,
        tools: List<LlmToolDefinition>,
    ): DomainResult<LlmResponse> {
        val system =
            if (userContextJson.isNotBlank()) "$systemPrompt\n\n$userContextJson" else systemPrompt
        val requestBody =
            buildJsonObject {
                put("model", model)
                put("max_tokens", 1024)
                put("system", system)
                put(
                    "messages",
                    buildJsonArray {
                        messages.forEach { msg ->
                            add(
                                buildJsonObject {
                                    put("role", if (msg.role == MessageRole.USER) "user" else "assistant")
                                    put("content", msg.content)
                                },
                            )
                        }
                    },
                )
                if (tools.isNotEmpty()) {
                    put(
                        "tools",
                        buildJsonArray {
                            tools.forEach { tool ->
                                add(
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put(
                                            "input_schema",
                                            json.parseToJsonElement(tool.inputSchemaJson),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }.toString()

        return try {
            var lastResponse: ClaudeHttpResponse? = null
            for (attempt in 0 until MAX_RETRIES) {
                val response = httpExecutor.post("$baseUrl/messages", apiKey, requestBody)
                if (response.statusCode == 429 || response.statusCode == 529) {
                    lastResponse = response
                    if (attempt < MAX_RETRIES - 1) {
                        val delayMs = RETRY_BASE_DELAY_MS * (1L shl attempt) // 2s, 4s, 8s
                        delay(delayMs)
                        continue
                    }
                } else if (response.statusCode != 200) {
                    return DomainResult.Err(
                        DomainError.LlmUnavailable("HTTP ${response.statusCode}: ${response.body}"),
                    )
                } else {
                    return parseResponse(response.body)
                }
            }
            DomainResult.Err(
                DomainError.LlmUnavailable("HTTP ${lastResponse!!.statusCode}: ${lastResponse.body}"),
            )
        } catch (e: Exception) {
            DomainResult.Err(DomainError.LlmUnavailable("Request failed: ${e.message}"))
        }
    }

    private fun parseResponse(body: String): DomainResult<LlmResponse> {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val content =
                root["content"]?.jsonArray
                    ?: return DomainResult.Err(DomainError.LlmUnavailable("Missing content in response"))

            val toolUseBlock =
                content.firstOrNull {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
                }
            if (toolUseBlock != null) {
                val obj = toolUseBlock.jsonObject
                val name =
                    obj["name"]?.jsonPrimitive?.content
                        ?: return DomainResult.Err(DomainError.LlmUnavailable("Missing tool name"))
                val id =
                    obj["id"]?.jsonPrimitive?.content
                        ?: return DomainResult.Err(DomainError.LlmUnavailable("Missing tool use id"))
                val input =
                    obj["input"]
                        ?: return DomainResult.Err(DomainError.LlmUnavailable("Missing tool input"))
                return DomainResult.Ok(LlmResponse.ToolUse(name, id, input.toString()))
            }

            val textBlock =
                content.firstOrNull {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "text"
                }
            if (textBlock != null) {
                val text =
                    textBlock.jsonObject["text"]?.jsonPrimitive?.content
                        ?: return DomainResult.Err(DomainError.LlmUnavailable("Missing text content"))
                return DomainResult.Ok(LlmResponse.Text(text))
            }

            DomainResult.Err(DomainError.LlmUnavailable("No recognizable content in response"))
        } catch (e: Exception) {
            DomainResult.Err(DomainError.LlmUnavailable("Parse error: ${e.message}"))
        }
    }
}
