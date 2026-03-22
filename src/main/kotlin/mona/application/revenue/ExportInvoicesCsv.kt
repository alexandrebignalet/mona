package mona.application.revenue

import mona.domain.model.InvoiceStatus
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository
import java.time.LocalDate

data class ExportInvoicesCsvCommand(
    val userId: UserId,
    val exportDate: LocalDate,
)

data class ExportInvoicesCsvResult(
    val csvContent: ByteArray,
    val filename: String,
    val invoiceCount: Int,
)

class ExportInvoicesCsv(
    private val invoiceRepository: InvoiceRepository,
    private val clientRepository: ClientRepository,
) {
    suspend fun execute(command: ExportInvoicesCsvCommand): ExportInvoicesCsvResult {
        val invoices = invoiceRepository.findByUser(command.userId).sortedBy { it.number.value }
        val clients = clientRepository.findByUser(command.userId).associateBy { it.id }

        val header = "invoice_number,status,issue_date,due_date,paid_date,client_name,line_items,total_ht,total_ttc,payment_method"
        val rows =
            invoices.map { invoice ->
                val clientName = clients[invoice.clientId]?.name ?: ""
                val statusStr =
                    when (invoice.status) {
                        is InvoiceStatus.Draft -> "DRAFT"
                        is InvoiceStatus.Sent -> "SENT"
                        is InvoiceStatus.Paid -> "PAID"
                        is InvoiceStatus.Overdue -> "OVERDUE"
                        is InvoiceStatus.Cancelled -> "CANCELLED"
                    }
                val paidDate = (invoice.status as? InvoiceStatus.Paid)?.date?.toString() ?: ""
                val paymentMethod = (invoice.status as? InvoiceStatus.Paid)?.method?.name ?: ""
                val lineItemsStr =
                    invoice.lineItems.joinToString(" | ") { item ->
                        val unitPrice = formatEuros(item.unitPriceHt.value)
                        val lineTotal = formatEuros(item.totalHt.value)
                        "${item.description};${item.quantity.toPlainString()};$unitPrice;$lineTotal"
                    }
                val totalHt = formatEuros(invoice.amountHt.value)

                listOf(
                    invoice.number.value,
                    statusStr,
                    invoice.issueDate.toString(),
                    invoice.dueDate.toString(),
                    paidDate,
                    clientName,
                    lineItemsStr,
                    totalHt,
                    totalHt,
                    paymentMethod,
                ).joinToString(",") { csvEscape(it) }
            }

        val csvText = (listOf(header) + rows).joinToString("\n")
        val filename = "mona-factures-${command.exportDate}.csv"

        return ExportInvoicesCsvResult(
            csvContent = csvText.toByteArray(Charsets.UTF_8),
            filename = filename,
            invoiceCount = invoices.size,
        )
    }

    private fun formatEuros(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val absCents = Math.abs(cents)
        val euros = absCents / 100
        val centsPart = absCents % 100
        return "$sign$euros.${centsPart.toString().padStart(2, '0')}"
    }

    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
