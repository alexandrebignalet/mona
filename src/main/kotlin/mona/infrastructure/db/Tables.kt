package mona.infrastructure.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

object UsersTable : Table("users") {
    val id = varchar("id", 36)
    val telegramId = long("telegram_id").uniqueIndex()
    val email = varchar("email", 255).nullable().uniqueIndex()
    val name = varchar("name", 255).nullable()
    val siren = varchar("siren", 9).nullable()
    val siret = varchar("siret", 14).nullable()
    val addressStreet = varchar("address_street", 500).nullable()
    val addressPostalCode = varchar("address_postal_code", 10).nullable()
    val addressCity = varchar("address_city", 255).nullable()
    val ibanEncrypted = binary("iban_encrypted").nullable()
    val activityType = varchar("activity_type", 20).nullable()
    val declarationPeriodicity = varchar("declaration_periodicity", 20).nullable()
    val confirmBeforeCreate = bool("confirm_before_create").default(true)
    val defaultPaymentDelayDays = integer("default_payment_delay_days").default(30)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object ClientsTable : Table("clients") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(UsersTable.id)
    val name = varchar("name", 255)
    val email = varchar("email", 255).nullable()
    val addressStreet = varchar("address_street", 500).nullable()
    val addressPostalCode = varchar("address_postal_code", 10).nullable()
    val addressCity = varchar("address_city", 255).nullable()
    val companyName = varchar("company_name", 255).nullable()
    val siret = varchar("siret", 14).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object InvoicesTable : Table("invoices") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(UsersTable.id)
    val clientId = varchar("client_id", 36).references(ClientsTable.id)
    val invoiceNumber = varchar("invoice_number", 20)
    val status = varchar("status", 20)
    val issueDate = date("issue_date")
    val dueDate = date("due_date")
    val paidDate = date("paid_date").nullable()
    val paymentMethod = varchar("payment_method", 20).nullable()
    val activityType = varchar("activity_type", 20)
    val pdfPath = varchar("pdf_path", 500).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object InvoiceLineItemsTable : Table("invoice_line_items") {
    val id = varchar("id", 36)
    val invoiceId = varchar("invoice_id", 36).references(InvoicesTable.id)
    val description = varchar("description", 1000)
    val quantity = decimal("quantity", 10, 4)
    val unitPriceHt = long("unit_price_ht")

    override val primaryKey = PrimaryKey(id)
}

object CreditNotesTable : Table("credit_notes") {
    val id = varchar("id", 36)
    val invoiceId = varchar("invoice_id", 36).references(InvoicesTable.id)
    val replacementInvoiceId = varchar("replacement_invoice_id", 36).nullable()
    val creditNoteNumber = varchar("credit_note_number", 20)
    val amountHt = long("amount_ht")
    val reason = varchar("reason", 1000).nullable()
    val issueDate = date("issue_date")
    val pdfPath = varchar("pdf_path", 500).nullable()

    override val primaryKey = PrimaryKey(id)
}

object ConversationMessagesTable : Table("conversation_messages") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(UsersTable.id)
    val role = varchar("role", 20)
    val content = text("content")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object UrssafRemindersTable : Table("urssaf_reminders") {
    val userId = varchar("user_id", 36).references(UsersTable.id)
    val periodKey = varchar("period_key", 20)
    val d7SentAt = timestamp("d7_sent_at").nullable()
    val d1SentAt = timestamp("d1_sent_at").nullable()

    override val primaryKey = PrimaryKey(userId, periodKey)
}
