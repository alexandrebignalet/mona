package mona.domain.model

import java.time.Instant

data class VatAlertRecord(
    val userId: UserId,
    val yearKey: String,
    val p80SentAt: Instant? = null,
    val p95SentAt: Instant? = null,
)
