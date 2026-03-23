package mona.infrastructure.db

import mona.domain.model.UrssafReminderRecord
import mona.domain.model.UserId
import mona.domain.port.UrssafReminderRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class ExposedUrssafReminderRepository : UrssafReminderRepository {
    override suspend fun findByUserAndPeriod(
        userId: UserId,
        periodKey: String,
    ): UrssafReminderRecord? =
        newSuspendedTransaction {
            UrssafRemindersTable.selectAll()
                .where {
                    (UrssafRemindersTable.userId eq userId.value) and
                        (UrssafRemindersTable.periodKey eq periodKey)
                }
                .firstOrNull()
                ?.let { row ->
                    UrssafReminderRecord(
                        userId = UserId(row[UrssafRemindersTable.userId]),
                        periodKey = row[UrssafRemindersTable.periodKey],
                        d7SentAt = row[UrssafRemindersTable.d7SentAt],
                        d1SentAt = row[UrssafRemindersTable.d1SentAt],
                    )
                }
        }

    override suspend fun save(record: UrssafReminderRecord) {
        newSuspendedTransaction {
            val exists =
                UrssafRemindersTable.selectAll()
                    .where {
                        (UrssafRemindersTable.userId eq record.userId.value) and
                            (UrssafRemindersTable.periodKey eq record.periodKey)
                    }
                    .firstOrNull() != null
            if (exists) {
                UrssafRemindersTable.update({
                    (UrssafRemindersTable.userId eq record.userId.value) and
                        (UrssafRemindersTable.periodKey eq record.periodKey)
                }) {
                    it[d7SentAt] = record.d7SentAt
                    it[d1SentAt] = record.d1SentAt
                }
            } else {
                UrssafRemindersTable.insert {
                    it[userId] = record.userId.value
                    it[periodKey] = record.periodKey
                    it[d7SentAt] = record.d7SentAt
                    it[d1SentAt] = record.d1SentAt
                }
            }
        }
    }
}
