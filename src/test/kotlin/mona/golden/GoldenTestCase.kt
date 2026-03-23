package mona.golden

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mona.domain.model.DomainResult
import mona.domain.model.UserId
import mona.domain.port.ConversationMessage
import mona.domain.port.LlmResponse
import mona.domain.port.MessageRole
import mona.infrastructure.llm.ClaudeApiClient
import mona.infrastructure.llm.PromptBuilder
import mona.infrastructure.llm.ToolDefinitions
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class GoldenTurn(val role: String, val content: String)

data class GoldenTestCase(
    val id: String,
    val category: String,
    val description: String,
    val conversationHistory: List<GoldenTurn>,
    val userMessage: String,
    val expectedTool: String,
    val expectedParams: Map<String, String>,
)

sealed class GoldenResult {
    data class ToolUsed(val toolName: String, val inputJson: String) : GoldenResult()

    data class TextResponse(val text: String) : GoldenResult()

    data class ApiError(val message: String) : GoldenResult()
}

object GoldenTestLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(resourcePath: String): List<GoldenTestCase> {
        val stream =
            GoldenTestLoader::class.java.getResourceAsStream(resourcePath)
                ?: error("Test resource not found: $resourcePath")
        val content = stream.bufferedReader().readText()
        val array = json.parseToJsonElement(content).jsonArray
        return array.map { el ->
            val obj = el.jsonObject
            GoldenTestCase(
                id = obj["id"]!!.jsonPrimitive.content,
                category = obj["category"]!!.jsonPrimitive.content,
                description = obj["description"]!!.jsonPrimitive.content,
                conversationHistory =
                    obj["conversationHistory"]?.jsonArray?.map { t ->
                        val turn = t.jsonObject
                        GoldenTurn(
                            role = turn["role"]!!.jsonPrimitive.content,
                            content = turn["content"]!!.jsonPrimitive.content,
                        )
                    } ?: emptyList(),
                userMessage = obj["userMessage"]!!.jsonPrimitive.content,
                expectedTool = obj["expectedTool"]!!.jsonPrimitive.content,
                expectedParams =
                    obj["expectedParams"]?.jsonObject?.mapValues { (_, v) ->
                        v.jsonPrimitive.contentOrNull ?: v.toString()
                    } ?: emptyMap(),
            )
        }
    }

    fun runCase(
        client: ClaudeApiClient,
        case: GoldenTestCase,
    ): GoldenResult {
        val fakeUserId = UserId("golden-test")
        val historyMessages =
            case.conversationHistory.mapIndexed { idx, turn ->
                ConversationMessage(
                    id = "turn-$idx",
                    userId = fakeUserId,
                    role = if (turn.role == "user") MessageRole.USER else MessageRole.ASSISTANT,
                    content = turn.content,
                    createdAt = Instant.now(),
                )
            }
        val currentMessage =
            ConversationMessage(
                id = "turn-${case.conversationHistory.size}",
                userId = fakeUserId,
                role = MessageRole.USER,
                content = case.userMessage,
                createdAt = Instant.now(),
            )
        val messages = historyMessages + currentMessage
        val userContextJson = """{"user_context":{"has_siren":false,"onboarding_step":"awaiting_siren"}}"""

        val result =
            runBlocking {
                client.complete(
                    systemPrompt = PromptBuilder.SYSTEM_PROMPT,
                    userContextJson = userContextJson,
                    messages = messages,
                    tools = ToolDefinitions.all,
                )
            }

        return when (result) {
            is DomainResult.Ok ->
                when (val r = result.value) {
                    is LlmResponse.ToolUse -> GoldenResult.ToolUsed(r.toolName, r.inputJson)
                    is LlmResponse.Text -> GoldenResult.TextResponse(r.text)
                }
            is DomainResult.Err -> GoldenResult.ApiError(result.error.toString())
        }
    }

    fun validateResult(
        case: GoldenTestCase,
        result: GoldenResult,
    ) {
        when (result) {
            is GoldenResult.ApiError -> error("API error for ${case.id}: ${result.message}")
            is GoldenResult.TextResponse -> {
                // The system prompt encourages the conversational tool, but Claude may return
                // plain text for purely conversational messages — accept both for "conversational"
                assertTrue(
                    case.expectedTool == "conversational",
                    "Expected tool '${case.expectedTool}' but got plain text response for ${case.id}: ${result.text.take(120)}",
                )
            }
            is GoldenResult.ToolUsed -> {
                assertEquals(
                    case.expectedTool,
                    result.toolName,
                    "Wrong tool for ${case.id} (\"${case.userMessage}\")",
                )
                validateParams(case, result.inputJson)
            }
        }
    }

    private fun validateParams(
        case: GoldenTestCase,
        inputJson: String,
    ) {
        if (case.expectedParams.isEmpty()) return
        val obj = json.parseToJsonElement(inputJson).jsonObject
        for ((key, expectedValue) in case.expectedParams) {
            val actualValue = obj[key]?.jsonPrimitive?.contentOrNull
            assertTrue(
                actualValue != null,
                "Expected param '$key' missing for ${case.id}. JSON: $inputJson",
            )
            assertTrue(
                actualValue.contains(expectedValue, ignoreCase = true),
                "For ${case.id}, param '$key': expected to contain '$expectedValue' (case-insensitive) but got '$actualValue'",
            )
        }
    }
}
