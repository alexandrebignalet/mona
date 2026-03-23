package mona.domain.port

import mona.domain.model.UrssafReminderRecord
import mona.domain.model.UserId

interface UrssafReminderRepository {
    suspend fun findByUserAndPeriod(
        userId: UserId,
        periodKey: String,
    ): UrssafReminderRecord?

    suspend fun save(record: UrssafReminderRecord)
}
