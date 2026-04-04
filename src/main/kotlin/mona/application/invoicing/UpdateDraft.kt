package mona.application.invoicing

import mona.application.EventDispatcher
import mona.domain.model.ActivityType
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.LineItem
import mona.domain.model.PaymentDelayDays
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.PdfPort
import mona.domain.port.UserRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class UpdateDraftCommand(
    val userId: UserId,
    val invoiceId: InvoiceId,
    val clientName: String? = null,
    val lineItems: List<LineItem>? = null,
    val issueDate: LocalDate? = null,
    val paymentDelay: PaymentDelayDays? = null,
    val activityType: ActivityType? = null,
)

data class UpdateDraftResult(val invoice: Invoice, val pdf: ByteArray)

class UpdateDraft(
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val invoiceRepository: InvoiceRepository,
    private val pdfPort: PdfPort,
    private val eventDispatcher: EventDispatcher,
) {
    suspend fun execute(command: UpdateDraftCommand): DomainResult<UpdateDraftResult> {
        val now = Instant.now()
        val user =
            userRepository.findById(command.userId)
                ?: return DomainResult.Err(DomainError.ProfileIncomplete(listOf("user")))

        val invoice =
            invoiceRepository.findById(command.invoiceId)
                ?: return DomainResult.Err(DomainError.InvoiceNotFound(command.invoiceId))

        val clientId =
            if (command.clientName != null) {
                resolveClient(command.userId, command.clientName, now).id
            } else {
                invoice.clientId
            }

        val updated =
            when (
                val r =
                    invoice.updateDraft(
                        clientId = clientId,
                        lineItems = command.lineItems ?: invoice.lineItems,
                        issueDate = command.issueDate ?: invoice.issueDate,
                        paymentDelay = command.paymentDelay,
                        activityType = command.activityType ?: invoice.activityType,
                    )
            ) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        val updatedClientId =
            updated.clientId ?: return DomainResult.Err(DomainError.ClientNotFound(""))
        val client =
            clientRepository.findById(updatedClientId)
                ?: return DomainResult.Err(DomainError.ClientNotFound(updatedClientId.value))

        val pdf =
            when (val r = pdfPort.generateInvoice(updated, user, client, null)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }
        invoiceRepository.save(updated)
        eventDispatcher.dispatch(emptyList())
        return DomainResult.Ok(UpdateDraftResult(updated, pdf))
    }

    private suspend fun resolveClient(
        userId: UserId,
        name: String,
        now: Instant,
    ): Client {
        val existing = clientRepository.findByUserAndName(userId, name)
        if (existing.isNotEmpty()) return existing.first()
        val client =
            Client(
                id = ClientId(UUID.randomUUID().toString()),
                userId = userId,
                name = name,
                email = null,
                address = null,
                companyName = null,
                siret = null,
                createdAt = now,
            )
        clientRepository.save(client)
        return client
    }
}
