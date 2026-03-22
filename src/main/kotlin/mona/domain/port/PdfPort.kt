package mona.domain.port

import mona.domain.model.Client
import mona.domain.model.CreditNote
import mona.domain.model.Invoice
import mona.domain.model.InvoiceNumber
import mona.domain.model.User

interface PdfPort {
    fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): ByteArray

    fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): ByteArray
}
