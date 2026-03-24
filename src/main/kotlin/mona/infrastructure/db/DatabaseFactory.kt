package mona.infrastructure.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager

object DatabaseFactory {
    fun init(dbPath: String = "mona.db"): Database {
        val url = "jdbc:sqlite:$dbPath"

        // Set PRAGMAs via raw JDBC before Exposed takes over
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA foreign_keys=ON;")
            }
        }

        val db = Database.connect(url = url, driver = "org.sqlite.JDBC")

        transaction(db) {
            SchemaUtils.create(
                UsersTable,
                ClientsTable,
                InvoicesTable,
                InvoiceLineItemsTable,
                CreditNotesTable,
                ConversationMessagesTable,
                UrssafRemindersTable,
                OnboardingRemindersTable,
                VatAlertsTable,
            )
        }
        return db
    }
}
