package mona.infrastructure.db

import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNote
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.PaymentMethod
import mona.domain.model.UserId
import mona.domain.port.InvoiceRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class ExposedInvoiceRepository : InvoiceRepository {
    override suspend fun findById(id: InvoiceId): Invoice? =
        newSuspendedTransaction {
            val row =
                InvoicesTable.selectAll()
                    .where { InvoicesTable.id eq id.value }
                    .firstOrNull() ?: return@newSuspendedTransaction null
            val lineItems = loadLineItems(id.value)
            val creditNote = loadCreditNote(id.value)
            row.toInvoice(lineItems, creditNote)
        }

    override suspend fun save(invoice: Invoice) {
        newSuspendedTransaction {
            val existing =
                InvoicesTable.selectAll()
                    .where { InvoicesTable.id eq invoice.id.value }
                    .firstOrNull()
            if (existing != null) {
                InvoicesTable.update({ InvoicesTable.id eq invoice.id.value }) {
                    it[status] = invoice.status.toDbString()
                    it[paidDate] = (invoice.status as? InvoiceStatus.Paid)?.date
                    it[paymentMethod] = (invoice.status as? InvoiceStatus.Paid)?.method?.name
                    it[pdfPath] = invoice.pdfPath
                    it[issueDate] = invoice.issueDate
                    it[dueDate] = invoice.dueDate
                }
                // Replace line items
                InvoiceLineItemsTable.deleteWhere { invoiceId eq invoice.id.value }
                insertLineItems(invoice.id.value, invoice.lineItems)
                // Handle credit note
                CreditNotesTable.deleteWhere { invoiceId eq invoice.id.value }
                invoice.creditNote?.let { insertCreditNote(invoice.id.value, it) }
            } else {
                InvoicesTable.insert {
                    it[id] = invoice.id.value
                    it[userId] = invoice.userId.value
                    it[clientId] = invoice.clientId.value
                    it[invoiceNumber] = invoice.number.value
                    it[status] = invoice.status.toDbString()
                    it[issueDate] = invoice.issueDate
                    it[dueDate] = invoice.dueDate
                    it[paidDate] = (invoice.status as? InvoiceStatus.Paid)?.date
                    it[paymentMethod] = (invoice.status as? InvoiceStatus.Paid)?.method?.name
                    it[activityType] = invoice.activityType.name
                    it[pdfPath] = invoice.pdfPath
                    it[createdAt] = invoice.createdAt
                }
                insertLineItems(invoice.id.value, invoice.lineItems)
                invoice.creditNote?.let { insertCreditNote(invoice.id.value, it) }
            }
        }
    }

    override suspend fun delete(id: InvoiceId) {
        newSuspendedTransaction {
            CreditNotesTable.deleteWhere { invoiceId eq id.value }
            InvoiceLineItemsTable.deleteWhere { invoiceId eq id.value }
            InvoicesTable.deleteWhere { InvoicesTable.id eq id.value }
        }
    }

    override suspend fun findLastNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): InvoiceNumber? =
        newSuspendedTransaction {
            val prefix = "F-${yearMonth.year}-${"%02d".format(yearMonth.monthValue)}-"
            InvoicesTable.selectAll()
                .where {
                    (InvoicesTable.userId eq userId.value) and
                        (InvoicesTable.invoiceNumber like "$prefix%")
                }
                .orderBy(InvoicesTable.invoiceNumber, SortOrder.DESC)
                .firstOrNull()
                ?.let { InvoiceNumber(it[InvoicesTable.invoiceNumber]) }
        }

    override suspend fun findLastCreditNoteNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): CreditNoteNumber? =
        newSuspendedTransaction {
            val prefix = "A-${yearMonth.year}-${"%02d".format(yearMonth.monthValue)}-"
            (CreditNotesTable innerJoin InvoicesTable)
                .selectAll()
                .where {
                    (InvoicesTable.userId eq userId.value) and
                        (CreditNotesTable.creditNoteNumber like "$prefix%")
                }
                .orderBy(CreditNotesTable.creditNoteNumber, SortOrder.DESC)
                .firstOrNull()
                ?.let { CreditNoteNumber(it[CreditNotesTable.creditNoteNumber]) }
        }

    override suspend fun findByUser(userId: UserId): List<Invoice> =
        newSuspendedTransaction {
            InvoicesTable.selectAll()
                .where { InvoicesTable.userId eq userId.value }
                .map { row ->
                    val id = row[InvoicesTable.id]
                    row.toInvoice(loadLineItems(id), loadCreditNote(id))
                }
        }

    override suspend fun findByUserAndStatus(
        userId: UserId,
        status: InvoiceStatus,
    ): List<Invoice> =
        newSuspendedTransaction {
            InvoicesTable.selectAll()
                .where {
                    (InvoicesTable.userId eq userId.value) and
                        (InvoicesTable.status eq status.toDbString())
                }
                .map { row ->
                    val id = row[InvoicesTable.id]
                    row.toInvoice(loadLineItems(id), loadCreditNote(id))
                }
        }

    override suspend fun findPaidInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<PaidInvoiceSnapshot> =
        newSuspendedTransaction {
            InvoicesTable.selectAll()
                .where {
                    (InvoicesTable.userId eq userId.value) and
                        (InvoicesTable.status eq "PAID") and
                        (InvoicesTable.paidDate greaterEq period.start) and
                        (InvoicesTable.paidDate lessEq period.endInclusive)
                }
                .map { row ->
                    PaidInvoiceSnapshot(
                        invoiceId = InvoiceId(row[InvoicesTable.id]),
                        amountHt = computeAmountHt(row[InvoicesTable.id]),
                        paidDate = row[InvoicesTable.paidDate]!!,
                        activityType = ActivityType.valueOf(row[InvoicesTable.activityType]),
                    )
                }
        }

    override suspend fun findCreditNotesInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<CreditNoteSnapshot> =
        newSuspendedTransaction {
            (CreditNotesTable innerJoin InvoicesTable)
                .selectAll()
                .where {
                    (InvoicesTable.userId eq userId.value) and
                        (CreditNotesTable.issueDate greaterEq period.start) and
                        (CreditNotesTable.issueDate lessEq period.endInclusive)
                }
                .map { row ->
                    CreditNoteSnapshot(
                        creditNoteNumber = CreditNoteNumber(row[CreditNotesTable.creditNoteNumber]),
                        amountHt = Cents(row[CreditNotesTable.amountHt]),
                        issueDate = row[CreditNotesTable.issueDate],
                        activityType = ActivityType.valueOf(row[InvoicesTable.activityType]),
                    )
                }
        }

    override suspend fun findSentOverdue(cutoffDate: LocalDate): List<Invoice> =
        newSuspendedTransaction {
            InvoicesTable.selectAll()
                .where {
                    (InvoicesTable.status eq "SENT") and
                        (InvoicesTable.dueDate less cutoffDate)
                }
                .map { row ->
                    val id = row[InvoicesTable.id]
                    row.toInvoice(loadLineItems(id), loadCreditNote(id))
                }
        }

    override suspend fun findSentDueOn(date: LocalDate): List<Invoice> =
        newSuspendedTransaction {
            InvoicesTable.selectAll()
                .where {
                    (InvoicesTable.status eq "SENT") and
                        (InvoicesTable.dueDate eq date)
                }
                .map { row ->
                    val id = row[InvoicesTable.id]
                    row.toInvoice(loadLineItems(id), loadCreditNote(id))
                }
        }

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> =
        newSuspendedTransaction {
            InvoicesTable.selectAll()
                .where {
                    (InvoicesTable.clientId eq clientId.value) and
                        (InvoicesTable.issueDate greaterEq since)
                }
                .map { row ->
                    val id = row[InvoicesTable.id]
                    row.toInvoice(loadLineItems(id), loadCreditNote(id))
                }
                .filter { it.amountHt == amountHt }
        }

    override suspend fun findByNumber(number: InvoiceNumber): List<Invoice> =
        newSuspendedTransaction {
            InvoicesTable.selectAll()
                .where { InvoicesTable.invoiceNumber eq number.value }
                .map { row ->
                    val id = row[InvoicesTable.id]
                    row.toInvoice(loadLineItems(id), loadCreditNote(id))
                }
        }

    private fun insertLineItems(
        invoiceId: String,
        lineItems: List<LineItem>,
    ) {
        for (item in lineItems) {
            InvoiceLineItemsTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[InvoiceLineItemsTable.invoiceId] = invoiceId
                it[description] = item.description
                it[quantity] = item.quantity
                it[unitPriceHt] = item.unitPriceHt.value
            }
        }
    }

    private fun insertCreditNote(
        invoiceId: String,
        cn: CreditNote,
    ) {
        CreditNotesTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[CreditNotesTable.invoiceId] = invoiceId
            it[replacementInvoiceId] = cn.replacementInvoiceId?.value
            it[creditNoteNumber] = cn.number.value
            it[amountHt] = cn.amountHt.value
            it[reason] = cn.reason
            it[issueDate] = cn.issueDate
            it[pdfPath] = cn.pdfPath
        }
    }

    private fun loadLineItems(invoiceId: String): List<LineItem> =
        InvoiceLineItemsTable.selectAll()
            .where { InvoiceLineItemsTable.invoiceId eq invoiceId }
            .map { row ->
                LineItem(
                    description = row[InvoiceLineItemsTable.description],
                    quantity = row[InvoiceLineItemsTable.quantity],
                    unitPriceHt = Cents(row[InvoiceLineItemsTable.unitPriceHt]),
                )
            }

    private fun loadCreditNote(invoiceId: String): CreditNote? =
        CreditNotesTable.selectAll()
            .where { CreditNotesTable.invoiceId eq invoiceId }
            .firstOrNull()
            ?.let { row ->
                CreditNote(
                    number = CreditNoteNumber(row[CreditNotesTable.creditNoteNumber]),
                    amountHt = Cents(row[CreditNotesTable.amountHt]),
                    reason = row[CreditNotesTable.reason] ?: "",
                    issueDate = row[CreditNotesTable.issueDate],
                    replacementInvoiceId = row[CreditNotesTable.replacementInvoiceId]?.let { InvoiceId(it) },
                    pdfPath = row[CreditNotesTable.pdfPath],
                )
            }

    private fun computeAmountHt(invoiceId: String): Cents {
        val items = loadLineItems(invoiceId)
        return Cents(items.sumOf { it.totalHt.value })
    }

    private fun ResultRow.toInvoice(
        lineItems: List<LineItem>,
        creditNote: CreditNote?,
    ): Invoice {
        val statusStr = this[InvoicesTable.status]
        val status =
            when (statusStr) {
                "DRAFT" -> InvoiceStatus.Draft
                "SENT" -> InvoiceStatus.Sent
                "PAID" ->
                    InvoiceStatus.Paid(
                        this[InvoicesTable.paidDate]!!,
                        PaymentMethod.valueOf(this[InvoicesTable.paymentMethod]!!),
                    )
                "OVERDUE" -> InvoiceStatus.Overdue
                "CANCELLED" -> InvoiceStatus.Cancelled
                else -> error("Unknown invoice status: $statusStr")
            }
        return Invoice(
            id = InvoiceId(this[InvoicesTable.id]),
            userId = UserId(this[InvoicesTable.userId]),
            clientId = ClientId(this[InvoicesTable.clientId]),
            number = InvoiceNumber(this[InvoicesTable.invoiceNumber]),
            status = status,
            issueDate = this[InvoicesTable.issueDate],
            dueDate = this[InvoicesTable.dueDate],
            activityType = ActivityType.valueOf(this[InvoicesTable.activityType]),
            lineItems = lineItems,
            pdfPath = this[InvoicesTable.pdfPath],
            creditNote = creditNote,
            createdAt = this[InvoicesTable.createdAt],
        )
    }

    private fun InvoiceStatus.toDbString(): String =
        when (this) {
            is InvoiceStatus.Draft -> "DRAFT"
            is InvoiceStatus.Sent -> "SENT"
            is InvoiceStatus.Paid -> "PAID"
            is InvoiceStatus.Overdue -> "OVERDUE"
            is InvoiceStatus.Cancelled -> "CANCELLED"
        }
}
