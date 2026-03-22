package mona.application.revenue

import mona.domain.model.Invoice
import mona.domain.model.InvoiceStatus
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository

data class GetUnpaidInvoicesCommand(val userId: UserId)

data class UnpaidInvoiceItem(
    val invoice: Invoice,
    val clientName: String,
)

data class GetUnpaidInvoicesResult(val items: List<UnpaidInvoiceItem>)

class GetUnpaidInvoices(
    private val invoiceRepository: InvoiceRepository,
    private val clientRepository: ClientRepository,
) {
    suspend fun execute(command: GetUnpaidInvoicesCommand): GetUnpaidInvoicesResult {
        val sent = invoiceRepository.findByUserAndStatus(command.userId, InvoiceStatus.Sent)
        val overdue = invoiceRepository.findByUserAndStatus(command.userId, InvoiceStatus.Overdue)
        val unpaid = (sent + overdue).sortedBy { it.dueDate }

        val clients = clientRepository.findByUser(command.userId).associateBy { it.id }
        val items =
            unpaid.map { invoice ->
                UnpaidInvoiceItem(
                    invoice = invoice,
                    clientName = clients[invoice.clientId]?.name ?: "",
                )
            }

        return GetUnpaidInvoicesResult(items)
    }
}
