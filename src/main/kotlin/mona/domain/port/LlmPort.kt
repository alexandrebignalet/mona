package mona.domain.port

import mona.domain.model.DomainResult

data class LlmToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

sealed class LlmResponse {
    data class Text(val text: String) : LlmResponse()

    data class ToolUse(
        val toolName: String,
        val toolUseId: String,
        val inputJson: String,
    ) : LlmResponse()
}

interface LlmPort {
    suspend fun complete(
        systemPrompt: String,
        userContextJson: String,
        messages: List<ConversationMessage>,
        tools: List<LlmToolDefinition>,
    ): DomainResult<LlmResponse>
}
