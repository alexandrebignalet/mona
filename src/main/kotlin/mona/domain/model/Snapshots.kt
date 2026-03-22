package mona.domain.model

import java.time.LocalDate

data class PaidInvoiceSnapshot(
    val invoiceId: InvoiceId,
    val amountHt: Cents,
    val paidDate: LocalDate,
    val activityType: ActivityType,
)

data class CreditNoteSnapshot(
    val creditNoteNumber: CreditNoteNumber,
    val amountHt: Cents,
    val issueDate: LocalDate,
    val activityType: ActivityType,
)
