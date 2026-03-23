package mona.domain.model

import java.time.Instant

data class UrssafReminderRecord(
    val userId: UserId,
    val periodKey: String,
    val d7SentAt: Instant? = null,
    val d1SentAt: Instant? = null,
)
