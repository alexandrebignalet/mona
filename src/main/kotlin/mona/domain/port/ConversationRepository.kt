package mona.domain.port

import mona.domain.model.UserId

data class ConversationMessage(
    val id: String,
    val userId: UserId,
    val role: MessageRole,
    val content: String,
    val createdAt: java.time.Instant,
)

enum class MessageRole { USER, ASSISTANT }

interface ConversationRepository {
    suspend fun save(message: ConversationMessage)

    suspend fun findRecent(
        userId: UserId,
        limit: Int = 3,
    ): List<ConversationMessage>

    suspend fun deleteByUser(userId: UserId)
}
