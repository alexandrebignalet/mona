package mona.domain.port

import mona.domain.model.UserId
import mona.domain.model.VatAlertRecord

interface VatAlertRepository {
    suspend fun findByUserAndYear(
        userId: UserId,
        yearKey: String,
    ): VatAlertRecord?

    suspend fun save(record: VatAlertRecord)
}
