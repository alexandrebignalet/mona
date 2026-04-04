package mona.infrastructure.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import java.sql.DriverManager

object DatabaseFactory {
    fun init(dbPath: String = "mona.db"): Database {
        val url = "jdbc:sqlite:$dbPath"

        // Set PRAGMAs and run migrations via raw JDBC before Exposed takes over
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA foreign_keys=ON;")
            }
            migrateInvoicesNullableUserClientFk(conn)
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

    /**
     * Migrates the invoices table so that user_id and client_id are nullable.
     * Required for GDPR: anonymized invoices retain null FKs after user/client deletion.
     * SQLite cannot ALTER COLUMN constraints, so the standard recreate-table pattern is used.
     */
    private fun migrateInvoicesNullableUserClientFk(conn: Connection) {
        // Skip if invoices table does not exist yet (new database)
        val tableExists =
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='invoices'",
                ).use { it.next() }
            }
        if (!tableExists) return

        // Check if migration is needed: are user_id or client_id still NOT NULL?
        var userIdNotNull = false
        var clientIdNotNull = false
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(invoices)").use { rs ->
                while (rs.next()) {
                    when (rs.getString("name")) {
                        "user_id" -> userIdNotNull = rs.getInt("notnull") == 1
                        "client_id" -> clientIdNotNull = rs.getInt("notnull") == 1
                    }
                }
            }
        }
        if (!userIdNotNull && !clientIdNotNull) return

        // Recreate invoices table with nullable user_id and client_id
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys=OFF")
            stmt.execute(
                """
                CREATE TABLE invoices_new (
                    id TEXT NOT NULL,
                    user_id TEXT REFERENCES users(id),
                    client_id TEXT REFERENCES clients(id),
                    invoice_number TEXT NOT NULL,
                    status TEXT NOT NULL,
                    issue_date DATE NOT NULL,
                    due_date DATE NOT NULL,
                    paid_date DATE,
                    payment_method TEXT,
                    activity_type TEXT NOT NULL,
                    pdf_path TEXT,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (id)
                )
                """.trimIndent(),
            )
            stmt.execute("INSERT INTO invoices_new SELECT * FROM invoices")
            stmt.execute("DROP TABLE invoices")
            stmt.execute("ALTER TABLE invoices_new RENAME TO invoices")
            stmt.execute("PRAGMA foreign_keys=ON")
        }
    }
}
