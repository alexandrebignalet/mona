package mona.application.payment

import mona.application.EventDispatcher
import mona.domain.model.DomainResult
import mona.domain.port.InvoiceRepository
import java.time.Instant
import java.time.LocalDate

class OverdueTransitionJob(
    private val invoiceRepository: InvoiceRepository,
    private val eventDispatcher: EventDispatcher,
) {
    suspend fun execute(today: LocalDate) {
        val cutoff = today.minusDays(3)
        val invoices = invoiceRepository.findSentOverdue(cutoff)
        for (invoice in invoices) {
            when (val result = invoice.markOverdue(Instant.now())) {
                is DomainResult.Ok -> {
                    val (updated, events) = result.value
                    invoiceRepository.save(updated)
                    eventDispatcher.dispatch(events)
                }
                is DomainResult.Err -> Unit
            }
        }
    }
}
