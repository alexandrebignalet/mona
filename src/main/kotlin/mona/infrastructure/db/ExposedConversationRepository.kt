package mona.infrastructure.db

import mona.domain.model.UserId
import mona.domain.port.ConversationMessage
import mona.domain.port.ConversationRepository
import mona.domain.port.MessageRole
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class ExposedConversationRepository : ConversationRepository {
    override suspend fun save(message: ConversationMessage) {
        newSuspendedTransaction {
            ConversationMessagesTable.insert {
                it[id] = message.id
                it[userId] = message.userId.value
                it[role] = message.role.name
                it[content] = message.content
                it[createdAt] = message.createdAt
            }
            pruneOldMessages(message.userId)
        }
    }

    override suspend fun findRecent(
        userId: UserId,
        limit: Int,
    ): List<ConversationMessage> =
        newSuspendedTransaction {
            ConversationMessagesTable.selectAll()
                .where { ConversationMessagesTable.userId eq userId.value }
                .orderBy(ConversationMessagesTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    ConversationMessage(
                        id = row[ConversationMessagesTable.id],
                        userId = UserId(row[ConversationMessagesTable.userId]),
                        role = MessageRole.valueOf(row[ConversationMessagesTable.role]),
                        content = row[ConversationMessagesTable.content],
                        createdAt = row[ConversationMessagesTable.createdAt],
                    )
                }
                .reversed()
        }

    override suspend fun deleteByUser(userId: UserId) {
        newSuspendedTransaction {
            ConversationMessagesTable.deleteWhere { ConversationMessagesTable.userId eq userId.value }
        }
    }

    private fun pruneOldMessages(userId: UserId) {
        val messagesToKeep =
            ConversationMessagesTable.selectAll()
                .where { ConversationMessagesTable.userId eq userId.value }
                .orderBy(ConversationMessagesTable.createdAt, SortOrder.DESC)
                .limit(3)
                .map { it[ConversationMessagesTable.id] }
        if (messagesToKeep.isNotEmpty()) {
            ConversationMessagesTable.deleteWhere {
                (ConversationMessagesTable.userId eq userId.value) and
                    (ConversationMessagesTable.id notInList messagesToKeep)
            }
        }
    }
}
