package mona.application.invoicing

import mona.application.EventDispatcher
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.UserId
import mona.domain.port.InvoiceRepository
import java.time.Instant

data class DeleteDraftCommand(
    val userId: UserId,
    val invoiceId: InvoiceId,
)

class DeleteDraft(
    private val invoiceRepository: InvoiceRepository,
    private val eventDispatcher: EventDispatcher,
) {
    suspend fun execute(command: DeleteDraftCommand): DomainResult<Invoice> {
        val invoice =
            invoiceRepository.findById(command.invoiceId)
                ?: return DomainResult.Err(DomainError.InvoiceNotFound(command.invoiceId))

        val transition =
            when (val r = invoice.cancel(null, Instant.now())) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        invoiceRepository.delete(command.invoiceId)
        eventDispatcher.dispatch(transition.events)
        return DomainResult.Ok(transition.invoice)
    }
}
