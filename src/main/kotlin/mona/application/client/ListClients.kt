package mona.application.client

import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository

data class ListClientsCommand(val userId: UserId)

data class ClientSummary(
    val client: Client,
    val invoiceCount: Int,
    val totalAmount: Cents,
)

data class ListClientsResult(val clients: List<ClientSummary>)

class ListClients(
    private val clientRepository: ClientRepository,
    private val invoiceRepository: InvoiceRepository,
) {
    suspend fun execute(command: ListClientsCommand): ListClientsResult {
        val clients = clientRepository.findByUser(command.userId)
        val allInvoices = invoiceRepository.findByUser(command.userId)
        val invoicesByClient = allInvoices.groupBy { it.clientId }

        val summaries =
            clients.map { client ->
                val invoices = invoicesByClient[client.id].orEmpty()
                val total = invoices.fold(Cents.ZERO) { acc, inv -> acc + inv.amountHt }
                ClientSummary(
                    client = client,
                    invoiceCount = invoices.size,
                    totalAmount = total,
                )
            }

        return ListClientsResult(summaries)
    }
}
