package mona.infrastructure

import kotlinx.coroutines.test.runTest
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PaymentMethod
import mona.domain.model.PostalAddress
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.ConversationMessage
import mona.domain.port.MessageRole
import mona.infrastructure.db.DatabaseFactory
import mona.infrastructure.db.ExposedClientRepository
import mona.infrastructure.db.ExposedConversationRepository
import mona.infrastructure.db.ExposedInvoiceRepository
import mona.infrastructure.db.ExposedUserRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseIntegrationTest {
    private val dbPath = "test-${UUID.randomUUID()}.db"
    private val userRepo = ExposedUserRepository()
    private val clientRepo = ExposedClientRepository()
    private val invoiceRepo = ExposedInvoiceRepository()
    private val conversationRepo = ExposedConversationRepository()
    private val now = Instant.parse("2026-03-22T10:00:00Z")
    private val userId = UserId(UUID.randomUUID().toString())
    private val clientId = ClientId(UUID.randomUUID().toString())

    @BeforeTest
    fun setup() {
        DatabaseFactory.init(dbPath)
    }

    @AfterTest
    fun teardown() {
        java.io.File(dbPath).delete()
        java.io.File("$dbPath-wal").delete()
        java.io.File("$dbPath-shm").delete()
    }

    private fun testUser() =
        User(
            id = userId,
            telegramId = 12345L,
            email = Email("test@test.com"),
            name = "Jean Dupont",
            siren = null,
            siret = null,
            address = PostalAddress("12 Rue Test", "75001", "Paris"),
            ibanEncrypted = null,
            activityType = ActivityType.BNC,
            declarationPeriodicity = DeclarationPeriodicity.QUARTERLY,
            confirmBeforeCreate = true,
            defaultPaymentDelayDays = PaymentDelayDays(30),
            createdAt = now,
        )

    private fun testClient() =
        Client(
            id = clientId,
            userId = userId,
            name = "ACME Corp",
            email = Email("acme@test.com"),
            address = null,
            companyName = "ACME Corporation",
            siret = null,
            createdAt = now,
        )

    // --- Database init test ---
    @Test
    fun `database initializes without errors`() {
        // If we got here, setup succeeded
        assertTrue(true)
    }

    // --- User tests ---
    @Test
    fun `save and retrieve user round-trip`() =
        runTest {
            val user = testUser()
            userRepo.save(user)
            val found = userRepo.findById(userId)
            assertNotNull(found)
            assertEquals(user.telegramId, found.telegramId)
            assertEquals(user.email, found.email)
            assertEquals(user.name, found.name)
            assertEquals(user.address, found.address)
            assertEquals(user.activityType, found.activityType)
        }

    @Test
    fun `findByTelegramId works`() =
        runTest {
            userRepo.save(testUser())
            val found = userRepo.findByTelegramId(12345L)
            assertNotNull(found)
            assertEquals(userId, found.id)
        }

    @Test
    fun `save updates existing user`() =
        runTest {
            val user = testUser()
            userRepo.save(user)
            val updated = user.copy(name = "Updated Name")
            userRepo.save(updated)
            val found = userRepo.findById(userId)
            assertEquals("Updated Name", found?.name)
        }

    // --- Client tests ---
    @Test
    fun `save and retrieve client`() =
        runTest {
            userRepo.save(testUser())
            val client = testClient()
            clientRepo.save(client)
            val found = clientRepo.findById(clientId)
            assertNotNull(found)
            assertEquals("ACME Corp", found.name)
        }

    @Test
    fun `findByUserAndName case-insensitive`() =
        runTest {
            userRepo.save(testUser())
            clientRepo.save(testClient())
            val found = clientRepo.findByUserAndName(userId, "acme")
            assertEquals(1, found.size)
            assertEquals("ACME Corp", found[0].name)
        }

    // --- Invoice tests ---
    @Test
    fun `save and retrieve invoice with line items`() =
        runTest {
            userRepo.save(testUser())
            clientRepo.save(testClient())
            val lineItems =
                listOf(
                    LineItem("Service A", BigDecimal(2), Cents(50000)),
                    LineItem("Service B", BigDecimal.ONE, Cents(30000)),
                )
            val result =
                Invoice.create(
                    InvoiceId(UUID.randomUUID().toString()),
                    userId,
                    clientId,
                    InvoiceNumber("F-2026-03-001"),
                    LocalDate.of(2026, 3, 22),
                    PaymentDelayDays(30),
                    ActivityType.BNC,
                    lineItems,
                    now,
                )
            val invoice = (result as DomainResult.Ok).value
            invoiceRepo.save(invoice)
            val found = invoiceRepo.findById(invoice.id)
            assertNotNull(found)
            assertEquals(2, found.lineItems.size)
            assertEquals(Cents(130000), found.amountHt)
            assertIs<InvoiceStatus.Draft>(found.status)
        }

    @Test
    fun `delete removes invoice and line items`() =
        runTest {
            userRepo.save(testUser())
            clientRepo.save(testClient())
            val invoiceId = InvoiceId(UUID.randomUUID().toString())
            val invoice =
                (
                    Invoice.create(
                        invoiceId, userId, clientId,
                        InvoiceNumber("F-2026-03-001"),
                        LocalDate.of(2026, 3, 22),
                        PaymentDelayDays(30), ActivityType.BNC,
                        listOf(LineItem("Test", BigDecimal.ONE, Cents(10000))),
                        now,
                    ) as DomainResult.Ok
                ).value
            invoiceRepo.save(invoice)
            invoiceRepo.delete(invoiceId)
            assertNull(invoiceRepo.findById(invoiceId))
        }

    @Test
    fun `findLastNumberInMonth returns highest number`() =
        runTest {
            userRepo.save(testUser())
            clientRepo.save(testClient())
            val items = listOf(LineItem("X", BigDecimal.ONE, Cents(10000)))
            for (i in 1..3) {
                val inv =
                    (
                        Invoice.create(
                            InvoiceId(UUID.randomUUID().toString()),
                            userId, clientId,
                            InvoiceNumber("F-2026-03-${"%03d".format(i)}"),
                            LocalDate.of(2026, 3, 22),
                            PaymentDelayDays(30), ActivityType.BNC, items, now,
                        ) as DomainResult.Ok
                    ).value
                invoiceRepo.save(inv)
            }
            val last =
                invoiceRepo.findLastNumberInMonth(
                    userId,
                    java.time.YearMonth.of(2026, 3),
                )
            assertEquals(InvoiceNumber("F-2026-03-003"), last)
        }

    @Test
    fun `findPaidInPeriod returns snapshots`() =
        runTest {
            userRepo.save(testUser())
            clientRepo.save(testClient())
            val items = listOf(LineItem("X", BigDecimal.ONE, Cents(50000)))
            val inv =
                (
                    Invoice.create(
                        InvoiceId(UUID.randomUUID().toString()),
                        userId, clientId,
                        InvoiceNumber("F-2026-03-001"),
                        LocalDate.of(2026, 3, 1),
                        PaymentDelayDays(30), ActivityType.BNC, items, now,
                    ) as DomainResult.Ok
                ).value
            val paid =
                (inv.markPaid(LocalDate.of(2026, 3, 15), PaymentMethod.VIREMENT, now) as DomainResult.Ok)
                    .value.invoice
            invoiceRepo.save(paid)
            val period = DeclarationPeriod.monthly(2026, 3)
            val snapshots = invoiceRepo.findPaidInPeriod(userId, period)
            assertEquals(1, snapshots.size)
            assertEquals(Cents(50000), snapshots[0].amountHt)
        }

    @Test
    fun `findSentOverdue returns overdue invoices`() =
        runTest {
            userRepo.save(testUser())
            clientRepo.save(testClient())
            val items = listOf(LineItem("X", BigDecimal.ONE, Cents(10000)))
            val inv =
                (
                    Invoice.create(
                        InvoiceId(UUID.randomUUID().toString()),
                        userId, clientId,
                        InvoiceNumber("F-2026-03-001"),
                        LocalDate.of(2026, 3, 1),
                        PaymentDelayDays(1), ActivityType.BNC, items, now,
                    ) as DomainResult.Ok
                ).value
            val sent = (inv.send(now) as DomainResult.Ok).value.invoice
            invoiceRepo.save(sent)
            val overdue = invoiceRepo.findSentOverdue(LocalDate.of(2026, 3, 10))
            assertEquals(1, overdue.size)
        }

    // --- Conversation tests ---
    @Test
    fun `save 5 messages keeps only 3`() =
        runTest {
            userRepo.save(testUser())
            for (i in 1..5) {
                conversationRepo.save(
                    ConversationMessage(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        role = if (i % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                        content = "Message $i",
                        createdAt = now.plusSeconds(i.toLong()),
                    ),
                )
            }
            val recent = conversationRepo.findRecent(userId, 3)
            assertEquals(3, recent.size)
            assertEquals("Message 3", recent[0].content)
            assertEquals("Message 4", recent[1].content)
            assertEquals("Message 5", recent[2].content)
        }
}
