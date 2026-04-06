package mona.application.invoicing

import mona.application.EventDispatcher
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.EmailPort
import mona.domain.port.InvoiceRepository
import mona.domain.port.PdfPort
import mona.domain.port.UserRepository
import java.time.Instant

data class SendInvoiceCommand(
    val userId: UserId,
    val invoiceId: InvoiceId,
    val plainIban: String?,
)

data class SendInvoiceResult(val invoice: Invoice, val pdf: ByteArray)

class SendInvoice(
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val invoiceRepository: InvoiceRepository,
    private val pdfPort: PdfPort,
    private val emailPort: EmailPort,
    private val eventDispatcher: EventDispatcher,
) {
    suspend fun execute(command: SendInvoiceCommand): DomainResult<SendInvoiceResult> {
        val now = Instant.now()
        val user =
            userRepository.findById(command.userId)
                ?: return DomainResult.Err(DomainError.ProfileIncomplete(listOf("user")))
        if (user.siren == null) return DomainResult.Err(DomainError.SirenRequired)

        val invoice =
            invoiceRepository.findById(command.invoiceId)
                ?: return DomainResult.Err(DomainError.InvoiceNotFound(command.invoiceId))
        val invoiceClientId =
            invoice.clientId ?: return DomainResult.Err(DomainError.ClientNotFound(""))
        val client =
            clientRepository.findById(invoiceClientId)
                ?: return DomainResult.Err(DomainError.ClientNotFound(invoiceClientId.value))
        if (client.email == null) return DomainResult.Err(DomainError.ProfileIncomplete(listOf("client.email")))

        val pdf =
            when (val r = pdfPort.generateInvoice(invoice, user, client, command.plainIban)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }
        val transition =
            when (val r = invoice.send(now)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        when (
            val r =
                emailPort.sendInvoice(
                    to = client.email.value,
                    subject = "Facture ${invoice.number.value}",
                    body = "Veuillez trouver ci-joint la facture ${invoice.number.value}.",
                    pdfAttachment = pdf,
                    filename = "${invoice.number.value}.pdf",
                )
        ) {
            is DomainResult.Err -> return r
            is DomainResult.Ok -> Unit
        }

        invoiceRepository.save(transition.invoice)
        eventDispatcher.dispatch(transition.events)
        return DomainResult.Ok(SendInvoiceResult(transition.invoice, pdf))
    }
}
