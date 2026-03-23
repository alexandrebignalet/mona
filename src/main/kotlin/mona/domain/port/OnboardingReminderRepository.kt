package mona.domain.port

import mona.domain.model.OnboardingReminderRecord
import mona.domain.model.UserId

interface OnboardingReminderRepository {
    suspend fun findByUser(userId: UserId): OnboardingReminderRecord?

    suspend fun save(record: OnboardingReminderRecord)
}
