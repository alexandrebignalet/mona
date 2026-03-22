package mona.application.client

import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository

data class GetClientHistoryCommand(
    val userId: UserId,
    val clientId: ClientId? = null,
    val clientName: String? = null,
)

sealed class GetClientHistoryResult {
    data class Found(val client: Client, val invoices: List<Invoice>) : GetClientHistoryResult()

    data class Ambiguous(val matches: List<Client>) : GetClientHistoryResult()
}

class GetClientHistory(
    private val clientRepository: ClientRepository,
    private val invoiceRepository: InvoiceRepository,
) {
    suspend fun execute(command: GetClientHistoryCommand): DomainResult<GetClientHistoryResult> {
        val client =
            when {
                command.clientId != null -> {
                    clientRepository.findById(command.clientId)
                        ?: return DomainResult.Err(DomainError.ClientNotFound(command.clientId.value))
                }
                command.clientName != null -> {
                    val matches = clientRepository.findByUserAndName(command.userId, command.clientName)
                    when {
                        matches.isEmpty() -> return DomainResult.Err(DomainError.ClientNotFound(command.clientName))
                        matches.size > 1 -> return DomainResult.Ok(GetClientHistoryResult.Ambiguous(matches))
                        else -> matches.first()
                    }
                }
                else -> return DomainResult.Err(DomainError.ClientNotFound("no identifier provided"))
            }

        val invoices =
            invoiceRepository.findByUser(command.userId)
                .filter { it.clientId == client.id }
                .sortedBy { it.issueDate }

        return DomainResult.Ok(GetClientHistoryResult.Found(client, invoices))
    }
}
