package mona.application.invoicing

import mona.domain.model.DomainResult
import mona.domain.model.InvoiceNumber
import mona.domain.port.InvoiceRepository
import mona.domain.port.MessagingPort

class HandleBouncedEmail(
    private val invoiceRepository: InvoiceRepository,
    private val messagingPort: MessagingPort,
) {
    suspend fun execute(
        invoiceNumber: InvoiceNumber,
        recipientEmail: String,
    ) {
        val invoices = invoiceRepository.findByNumber(invoiceNumber)
        for (invoice in invoices) {
            val reverted =
                when (val r = invoice.revertToDraft()) {
                    is DomainResult.Err -> continue
                    is DomainResult.Ok -> r.value
                }
            invoiceRepository.save(reverted)
            messagingPort.sendMessage(
                invoice.userId,
                "📧 L'email à $recipientEmail pour la facture ${invoiceNumber.value} " +
                    "a échoué — l'adresse est peut-être incorrecte.\n" +
                    "Tu veux me donner une nouvelle adresse pour ce client ?",
            )
        }
    }
}
