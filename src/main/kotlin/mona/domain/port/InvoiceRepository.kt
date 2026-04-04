package mona.domain.port

import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.UserId
import java.time.LocalDate
import java.time.YearMonth

interface InvoiceRepository {
    suspend fun findById(id: InvoiceId): Invoice?

    suspend fun save(invoice: Invoice)

    suspend fun delete(id: InvoiceId)

    suspend fun findLastNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): InvoiceNumber?

    suspend fun findLastCreditNoteNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): CreditNoteNumber?

    suspend fun findByUser(userId: UserId): List<Invoice>

    suspend fun findByUserAndStatus(
        userId: UserId,
        status: InvoiceStatus,
    ): List<Invoice>

    suspend fun findPaidInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<PaidInvoiceSnapshot>

    suspend fun findCreditNotesInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<CreditNoteSnapshot>

    suspend fun findSentOverdue(cutoffDate: LocalDate): List<Invoice>

    suspend fun findSentDueOn(date: LocalDate): List<Invoice>

    suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice>

    suspend fun findByNumber(number: InvoiceNumber): List<Invoice>

    suspend fun anonymizeByUser(userId: UserId)
}
