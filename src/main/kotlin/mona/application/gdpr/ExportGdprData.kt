package mona.application.gdpr

import mona.application.revenue.ExportInvoicesCsv
import mona.application.revenue.ExportInvoicesCsvCommand
import mona.domain.model.DomainResult
import mona.domain.model.InvoiceStatus
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.CryptoPort
import mona.domain.port.InvoiceRepository
import mona.domain.port.PdfPort
import mona.domain.port.UserRepository
import java.time.LocalDate

data class ExportGdprDataResult(
    val csvBytes: ByteArray,
    val csvFilename: String,
    val invoicePdfs: List<Pair<ByteArray, String>>,
    val creditNotePdfs: List<Pair<ByteArray, String>>,
    val profileJsonBytes: ByteArray,
)

class ExportGdprData(
    private val userRepository: UserRepository,
    private val invoiceRepository: InvoiceRepository,
    private val clientRepository: ClientRepository,
    private val exportCsv: ExportInvoicesCsv,
    private val pdfPort: PdfPort,
    private val cryptoPort: CryptoPort,
) {
    suspend fun execute(userId: UserId): ExportGdprDataResult {
        val user = userRepository.findById(userId) ?: error("User not found: ${userId.value}")
        val plainIban = user.ibanEncrypted?.let { cryptoPort.decrypt(it) }

        val csvResult = exportCsv.execute(ExportInvoicesCsvCommand(userId, LocalDate.now()))

        val clients = clientRepository.findByUser(userId).associateBy { it.id }
        val nonDraftInvoices = invoiceRepository.findByUser(userId).filter { it.status !is InvoiceStatus.Draft }

        val invoicePdfs = mutableListOf<Pair<ByteArray, String>>()
        val creditNotePdfs = mutableListOf<Pair<ByteArray, String>>()

        for (invoice in nonDraftInvoices.sortedBy { it.number.value }) {
            val client = invoice.clientId?.let { clients[it] } ?: continue
            val pdfResult = pdfPort.generateInvoice(invoice, user, client, plainIban)
            if (pdfResult is DomainResult.Ok) {
                invoicePdfs += Pair(pdfResult.value, "${invoice.number.value}.pdf")
            }
            val creditNote = invoice.creditNote
            if (creditNote != null) {
                val cnResult = pdfPort.generateCreditNote(creditNote, invoice.number, user, client, plainIban)
                if (cnResult is DomainResult.Ok) {
                    creditNotePdfs += Pair(cnResult.value, "${creditNote.number.value}.pdf")
                }
            }
        }

        val profileJsonBytes = buildProfileJson(user, plainIban != null).toByteArray(Charsets.UTF_8)

        return ExportGdprDataResult(
            csvBytes = csvResult.csvContent,
            csvFilename = csvResult.filename,
            invoicePdfs = invoicePdfs,
            creditNotePdfs = creditNotePdfs,
            profileJsonBytes = profileJsonBytes,
        )
    }

    private fun buildProfileJson(
        user: User,
        hasIban: Boolean,
    ): String {
        val lines =
            listOf(
                "  \"nom\": ${jsonStr(user.name)}",
                "  \"email\": ${jsonStr(user.email?.value)}",
                "  \"siren\": ${jsonStr(user.siren?.value)}",
                "  \"siret\": ${jsonStr(user.siret?.value)}",
                "  \"type_activite\": ${jsonStr(user.activityType?.name)}",
                "  \"periodicite_urssaf\": ${jsonStr(user.declarationPeriodicity?.name)}",
                "  \"delai_paiement_jours\": ${user.defaultPaymentDelayDays.value}",
                "  \"adresse_rue\": ${jsonStr(user.address?.street)}",
                "  \"adresse_code_postal\": ${jsonStr(user.address?.postalCode)}",
                "  \"adresse_ville\": ${jsonStr(user.address?.city)}",
                "  \"iban_enregistre\": $hasIban",
            )
        return "{\n${lines.joinToString(",\n")}\n}"
    }

    private fun jsonStr(value: String?): String =
        if (value != null) {
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            "null"
        }
}
