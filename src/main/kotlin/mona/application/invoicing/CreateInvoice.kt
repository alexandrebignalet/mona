package mona.application.invoicing

import mona.application.EventDispatcher
import mona.domain.model.ActivityType
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceNumbering
import mona.domain.model.LineItem
import mona.domain.model.PaymentDelayDays
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.PdfPort
import mona.domain.port.UserRepository
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class CreateInvoiceCommand(
    val userId: UserId,
    val clientName: String,
    val lineItems: List<LineItem>,
    val issueDate: LocalDate,
    val activityType: ActivityType,
    val paymentDelay: PaymentDelayDays,
)

sealed class CreateInvoiceResult {
    data class Created(val invoice: Invoice, val pdf: ByteArray) : CreateInvoiceResult()

    data class DuplicateWarning(
        val invoice: Invoice,
        val pdf: ByteArray,
        val existingNumber: InvoiceNumber,
    ) : CreateInvoiceResult()
}

class CreateInvoice(
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val invoiceRepository: InvoiceRepository,
    private val pdfPort: PdfPort,
    private val eventDispatcher: EventDispatcher,
) {
    suspend fun execute(command: CreateInvoiceCommand): DomainResult<CreateInvoiceResult> {
        val now = Instant.now()
        val user =
            userRepository.findById(command.userId)
                ?: return DomainResult.Err(DomainError.ProfileIncomplete(listOf("user")))

        val client = resolveClient(command.userId, command.clientName, now)

        val yearMonth = YearMonth.from(command.issueDate)
        val lastNumber = invoiceRepository.findLastNumberInMonth(command.userId, yearMonth)
        val number =
            when (val r = InvoiceNumbering.next(yearMonth, lastNumber)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        val invoice =
            when (
                val r =
                    Invoice.create(
                        id = InvoiceId(UUID.randomUUID().toString()),
                        userId = command.userId,
                        clientId = client.id,
                        number = number,
                        issueDate = command.issueDate,
                        paymentDelay = command.paymentDelay,
                        activityType = command.activityType,
                        lineItems = command.lineItems,
                        now = now,
                    )
            ) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }

        val duplicates =
            invoiceRepository.findByClientAndAmountSince(
                client.id,
                invoice.amountHt,
                command.issueDate.minusDays(2),
            )

        val pdf =
            when (val r = pdfPort.generateInvoice(invoice, user, client, null)) {
                is DomainResult.Err -> return r
                is DomainResult.Ok -> r.value
            }
        invoiceRepository.save(invoice)
        eventDispatcher.dispatch(emptyList())

        return if (duplicates.isNotEmpty()) {
            DomainResult.Ok(CreateInvoiceResult.DuplicateWarning(invoice, pdf, duplicates.first().number))
        } else {
            DomainResult.Ok(CreateInvoiceResult.Created(invoice, pdf))
        }
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
