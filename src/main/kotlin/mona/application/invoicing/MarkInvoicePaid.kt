package mona.application.invoicing

import mona.application.EventDispatcher
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.PaymentMethod
import mona.domain.model.UserId
import mona.domain.port.InvoiceRepository
import java.time.Instant
import java.time.LocalDate

data class MarkInvoicePaidCommand(
    val userId: UserId,
    val invoiceId: InvoiceId,
    val paymentDate: LocalDate,
    val paymentMethod: PaymentMethod,
)

class MarkInvoicePaid(
    private val invoiceRepository: InvoiceRepository,
    private val eventDispatcher: EventDispatcher,
) {
    suspend fun execute(command: MarkInvoicePaidCommand): DomainResult<Invoice> {
        val invoice =
            invoiceRepository.findById(command.invoiceId)
                ?: return DomainResult.Err(DomainError.InvoiceNotFound(command.invoiceId))

        val transition =
            when (val r = invoice.markPaid(command.paymentDate, command.paymentMethod, Instant.now())) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        invoiceRepository.save(transition.invoice)
        eventDispatcher.dispatch(transition.events)
        return DomainResult.Ok(transition.invoice)
    }
}
