package mona.application.payment

import mona.domain.model.Cents
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.MessagingPort
import java.time.LocalDate

class PaymentCheckInJob(
    private val invoiceRepository: InvoiceRepository,
    private val clientRepository: ClientRepository,
    private val messagingPort: MessagingPort,
) {
    suspend fun execute(today: LocalDate) {
        val yesterday = today.minusDays(1)
        val invoices = invoiceRepository.findSentDueOn(yesterday)
        if (invoices.isEmpty()) return

        val byUser = invoices.groupBy { it.userId }
        for ((nullableUserId, userInvoices) in byUser) {
            val userId = nullableUserId ?: continue
            val clients = clientRepository.findByUser(userId).associateBy { it.id }
            val message =
                if (userInvoices.size == 1) {
                    val inv = userInvoices.first()
                    val clientName = inv.clientId?.let { clients[it] }?.name ?: "?"
                    "La facture ${inv.number.value} de $clientName (${formatCents(inv.amountHt)}) " +
                        "devait être payée hier — c'est fait ?"
                } else {
                    val sb = StringBuilder()
                    sb.append("${userInvoices.size} factures arrivaient à échéance hier :\n")
                    for (inv in userInvoices) {
                        val clientName = inv.clientId?.let { clients[it] }?.name ?: "?"
                        sb.append("→ $clientName — ${formatCents(inv.amountHt)} (${inv.number.value})\n")
                    }
                    sb.append("Lesquels t'ont payé ?")
                    sb.toString()
                }
            messagingPort.sendMessage(userId, message)
        }
    }

    private fun formatCents(cents: Cents): String {
        val v = cents.value
        val abs = if (v < 0) -v else v
        val euros = abs / 100
        val centsPart = abs % 100
        val prefix = if (v < 0) "-" else ""
        return if (centsPart == 0L) "$prefix$euros€" else "$prefix$euros,${centsPart.toString().padStart(2, '0')}€"
    }
}
