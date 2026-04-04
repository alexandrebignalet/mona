package mona.application.onboarding

import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.PdfPort
import mona.domain.port.UserRepository

data class FinalizeInvoiceCommand(
    val userId: UserId,
    val invoiceId: InvoiceId,
    val plainIban: String?,
)

data class FinalizeInvoiceResult(val invoice: Invoice, val pdf: ByteArray)

class FinalizeInvoice(
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val invoiceRepository: InvoiceRepository,
    private val pdfPort: PdfPort,
) {
    suspend fun execute(command: FinalizeInvoiceCommand): DomainResult<FinalizeInvoiceResult> {
        val user =
            userRepository.findById(command.userId)
                ?: return DomainResult.Err(DomainError.ProfileIncomplete(listOf("user")))
        if (user.siren == null) return DomainResult.Err(DomainError.SirenRequired())

        val invoice =
            invoiceRepository.findById(command.invoiceId)
                ?: return DomainResult.Err(DomainError.InvoiceNotFound(command.invoiceId))

        val invoiceClientId =
            invoice.clientId ?: return DomainResult.Err(DomainError.ClientNotFound(""))
        val client =
            clientRepository.findById(invoiceClientId)
                ?: return DomainResult.Err(DomainError.ClientNotFound(invoiceClientId.value))

        val pdf =
            when (val r = pdfPort.generateInvoice(invoice, user, client, command.plainIban)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }
        return DomainResult.Ok(FinalizeInvoiceResult(invoice, pdf))
    }
}
