package mona.infrastructure.db

import mona.domain.model.OnboardingReminderRecord
import mona.domain.model.UserId
import mona.domain.port.OnboardingReminderRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class ExposedOnboardingReminderRepository : OnboardingReminderRepository {
    override suspend fun findByUser(userId: UserId): OnboardingReminderRecord? =
        newSuspendedTransaction {
            OnboardingRemindersTable.selectAll()
                .where { OnboardingRemindersTable.userId eq userId.value }
                .firstOrNull()
                ?.let { row ->
                    OnboardingReminderRecord(
                        userId = UserId(row[OnboardingRemindersTable.userId]),
                        r1SentAt = row[OnboardingRemindersTable.r1SentAt],
                        r2SentAt = row[OnboardingRemindersTable.r2SentAt],
                    )
                }
        }

    override suspend fun save(record: OnboardingReminderRecord) {
        newSuspendedTransaction {
            val exists =
                OnboardingRemindersTable.selectAll()
                    .where { OnboardingRemindersTable.userId eq record.userId.value }
                    .firstOrNull() != null
            if (exists) {
                OnboardingRemindersTable.update({ OnboardingRemindersTable.userId eq record.userId.value }) {
                    it[r1SentAt] = record.r1SentAt
                    it[r2SentAt] = record.r2SentAt
                }
            } else {
                OnboardingRemindersTable.insert {
                    it[userId] = record.userId.value
                    it[r1SentAt] = record.r1SentAt
                    it[r2SentAt] = record.r2SentAt
                }
            }
        }
    }
}
