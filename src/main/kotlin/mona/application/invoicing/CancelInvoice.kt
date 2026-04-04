package mona.application.invoicing

import mona.application.EventDispatcher
import mona.domain.model.CreditNote
import mona.domain.model.CreditNoteNumbering
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.PdfPort
import mona.domain.port.UserRepository
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class CancelInvoiceCommand(
    val userId: UserId,
    val invoiceId: InvoiceId,
    val reason: String = "",
    val issueDate: LocalDate,
)

data class CancelInvoiceResult(
    val invoice: Invoice,
    val creditNotePdf: ByteArray,
)

class CancelInvoice(
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val invoiceRepository: InvoiceRepository,
    private val pdfPort: PdfPort,
    private val eventDispatcher: EventDispatcher,
) {
    suspend fun execute(command: CancelInvoiceCommand): DomainResult<CancelInvoiceResult> {
        val now = Instant.now()

        val user =
            userRepository.findById(command.userId)
                ?: return DomainResult.Err(DomainError.ProfileIncomplete(listOf("user")))

        val invoice =
            invoiceRepository.findById(command.invoiceId)
                ?: return DomainResult.Err(DomainError.InvoiceNotFound(command.invoiceId))

        val invoiceClientId =
            invoice.clientId ?: return DomainResult.Err(DomainError.ClientNotFound(""))
        val client =
            clientRepository.findById(invoiceClientId)
                ?: return DomainResult.Err(DomainError.ClientNotFound(invoiceClientId.value))

        val yearMonth = YearMonth.from(command.issueDate)
        val lastCnNumber = invoiceRepository.findLastCreditNoteNumberInMonth(command.userId, yearMonth)
        val creditNoteNumber =
            when (val r = CreditNoteNumbering.next(yearMonth, lastCnNumber)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        val creditNote =
            CreditNote(
                number = creditNoteNumber,
                amountHt = invoice.amountHt,
                reason = command.reason,
                issueDate = command.issueDate,
                replacementInvoiceId = null,
                pdfPath = null,
            )

        val transition =
            when (val r = invoice.cancel(creditNote, now)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        val creditNotePdf =
            when (val r = pdfPort.generateCreditNote(creditNote, invoice.number, user, client, null)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        invoiceRepository.save(transition.invoice)
        eventDispatcher.dispatch(transition.events)
        return DomainResult.Ok(CancelInvoiceResult(transition.invoice, creditNotePdf))
    }
}
