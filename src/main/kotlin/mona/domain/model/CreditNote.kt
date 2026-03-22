package mona.domain.model

import java.time.LocalDate

data class CreditNote(
    val number: CreditNoteNumber,
    val amountHt: Cents,
    val reason: String,
    val issueDate: LocalDate,
    val replacementInvoiceId: InvoiceId?,
    val pdfPath: String?,
)
