package mona.domain.model

import java.time.Instant

data class OnboardingReminderRecord(
    val userId: UserId,
    val r1SentAt: Instant? = null,
    val r2SentAt: Instant? = null,
)
