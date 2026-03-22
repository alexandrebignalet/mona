package mona.application.client

import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.PostalAddress
import mona.domain.model.Siret
import mona.domain.model.UserId
import mona.domain.port.ClientRepository

data class UpdateClientCommand(
    val userId: UserId,
    val clientId: ClientId? = null,
    val clientName: String? = null,
    val newName: String? = null,
    val email: Email? = null,
    val address: PostalAddress? = null,
    val companyName: String? = null,
    val siret: Siret? = null,
)

sealed class UpdateClientResult {
    data class Updated(val client: Client) : UpdateClientResult()

    data class Ambiguous(val matches: List<Client>) : UpdateClientResult()
}

class UpdateClient(
    private val clientRepository: ClientRepository,
) {
    suspend fun execute(command: UpdateClientCommand): DomainResult<UpdateClientResult> {
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
                        matches.size > 1 -> return DomainResult.Ok(UpdateClientResult.Ambiguous(matches))
                        else -> matches.first()
                    }
                }
                else -> return DomainResult.Err(DomainError.ClientNotFound("no identifier provided"))
            }

        val updated =
            client.copy(
                name = command.newName ?: client.name,
                email = command.email ?: client.email,
                address = command.address ?: client.address,
                companyName = command.companyName ?: client.companyName,
                siret = command.siret ?: client.siret,
            )
        clientRepository.save(updated)
        return DomainResult.Ok(UpdateClientResult.Updated(updated))
    }
}
