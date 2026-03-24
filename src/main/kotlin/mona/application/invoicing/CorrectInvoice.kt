package mona.application.invoicing

import mona.application.EventDispatcher
import mona.domain.model.ActivityType
import mona.domain.model.CreditNote
import mona.domain.model.CreditNoteNumbering
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumbering
import mona.domain.model.LineItem
import mona.domain.model.PaymentDelayDays
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.PdfPort
import mona.domain.port.UserRepository
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class CorrectInvoiceCommand(
    val userId: UserId,
    val invoiceId: InvoiceId,
    val correctedLineItems: List<LineItem>,
    val correctedActivityType: ActivityType? = null,
    val correctedPaymentDelay: PaymentDelayDays? = null,
    val creditNoteReason: String = "",
    val issueDate: LocalDate,
)

data class CorrectInvoiceResult(
    val cancelledInvoice: Invoice,
    val newInvoice: Invoice,
    val creditNotePdf: ByteArray,
    val newInvoicePdf: ByteArray,
)

class CorrectInvoice(
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val invoiceRepository: InvoiceRepository,
    private val pdfPort: PdfPort,
    private val eventDispatcher: EventDispatcher,
) {
    suspend fun execute(command: CorrectInvoiceCommand): DomainResult<CorrectInvoiceResult> {
        val now = Instant.now()

        val user =
            userRepository.findById(command.userId)
                ?: return DomainResult.Err(DomainError.ProfileIncomplete(listOf("user")))

        val original =
            invoiceRepository.findById(command.invoiceId)
                ?: return DomainResult.Err(DomainError.InvoiceNotFound(command.invoiceId))

        val client =
            clientRepository.findById(original.clientId)
                ?: return DomainResult.Err(DomainError.ClientNotFound(original.clientId.value))

        val yearMonth = YearMonth.from(command.issueDate)

        // Reserve new invoice ID first so the credit note can reference it
        val newInvoiceId = InvoiceId(UUID.randomUUID().toString())

        val lastCnNumber = invoiceRepository.findLastCreditNoteNumberInMonth(command.userId, yearMonth)
        val creditNoteNumber =
            when (val r = CreditNoteNumbering.next(yearMonth, lastCnNumber)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        val creditNote =
            CreditNote(
                number = creditNoteNumber,
                amountHt = original.amountHt,
                reason = command.creditNoteReason,
                issueDate = command.issueDate,
                replacementInvoiceId = newInvoiceId,
                pdfPath = null,
            )

        val cancelTransition =
            when (val r = original.cancel(creditNote, now)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        val lastInvoiceNumber = invoiceRepository.findLastNumberInMonth(command.userId, yearMonth)
        val newNumber =
            when (val r = InvoiceNumbering.next(yearMonth, lastInvoiceNumber)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        val newInvoice =
            when (
                val r =
                    Invoice.create(
                        id = newInvoiceId,
                        userId = command.userId,
                        clientId = original.clientId,
                        number = newNumber,
                        issueDate = command.issueDate,
                        paymentDelay =
                            command.correctedPaymentDelay ?: PaymentDelayDays(
                                original.dueDate.toEpochDay().minus(original.issueDate.toEpochDay()).toInt(),
                            ),
                        activityType = command.correctedActivityType ?: original.activityType,
                        lineItems = command.correctedLineItems,
                        now = now,
                    )
            ) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        val creditNotePdf =
            when (val r = pdfPort.generateCreditNote(creditNote, original.number, user, client, null)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }
        val newInvoicePdf =
            when (val r = pdfPort.generateInvoice(newInvoice, user, client, null)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        invoiceRepository.save(cancelTransition.invoice)
        invoiceRepository.save(newInvoice)
        eventDispatcher.dispatch(cancelTransition.events)
        return DomainResult.Ok(
            CorrectInvoiceResult(
                cancelledInvoice = cancelTransition.invoice,
                newInvoice = newInvoice,
                creditNotePdf = creditNotePdf,
                newInvoicePdf = newInvoicePdf,
            ),
        )
    }
}
