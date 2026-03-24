package mona.infrastructure.db

import mona.domain.model.UserId
import mona.domain.model.VatAlertRecord
import mona.domain.port.VatAlertRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class ExposedVatAlertRepository : VatAlertRepository {
    override suspend fun findByUserAndYear(
        userId: UserId,
        yearKey: String,
    ): VatAlertRecord? =
        newSuspendedTransaction {
            VatAlertsTable.selectAll()
                .where {
                    (VatAlertsTable.userId eq userId.value) and
                        (VatAlertsTable.yearKey eq yearKey)
                }
                .firstOrNull()
                ?.let { row ->
                    VatAlertRecord(
                        userId = UserId(row[VatAlertsTable.userId]),
                        yearKey = row[VatAlertsTable.yearKey],
                        p80SentAt = row[VatAlertsTable.p80SentAt],
                        p95SentAt = row[VatAlertsTable.p95SentAt],
                    )
                }
        }

    override suspend fun save(record: VatAlertRecord) {
        newSuspendedTransaction {
            val exists =
                VatAlertsTable.selectAll()
                    .where {
                        (VatAlertsTable.userId eq record.userId.value) and
                            (VatAlertsTable.yearKey eq record.yearKey)
                    }
                    .firstOrNull() != null
            if (exists) {
                VatAlertsTable.update({
                    (VatAlertsTable.userId eq record.userId.value) and
                        (VatAlertsTable.yearKey eq record.yearKey)
                }) {
                    it[p80SentAt] = record.p80SentAt
                    it[p95SentAt] = record.p95SentAt
                }
            } else {
                VatAlertsTable.insert {
                    it[userId] = record.userId.value
                    it[yearKey] = record.yearKey
                    it[p80SentAt] = record.p80SentAt
                    it[p95SentAt] = record.p95SentAt
                }
            }
        }
    }
}
