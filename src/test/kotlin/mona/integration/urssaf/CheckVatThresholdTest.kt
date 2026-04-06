package mona.integration.urssaf

import kotlinx.coroutines.runBlocking
import mona.application.urssaf.CheckVatThreshold
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.DomainEvent
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaymentMethod
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// BIC_SERVICE threshold = 36_800 euros = 3_680_000 cents
private const val BIC_SERVICE_THRESHOLD = 36_800_00L
private const val P80_AMOUNT = 29_440_01L // just over 80%
private const val P95_AMOUNT = 34_960_01L // just over 95%

private fun paidEvent(
    userId: mona.domain.model.UserId,
    amount: Long,
    activityType: ActivityType = ActivityType.BIC_SERVICE,
    paidDate: LocalDate = LocalDate.of(2026, 4, 10),
) = DomainEvent.InvoicePaid(
    invoiceId = InvoiceId("test-id"),
    invoiceNumber = InvoiceNumber("F-2026-04-001"),
    amount = Cents(amount),
    paidDate = paidDate,
    method = PaymentMethod.VIREMENT,
    activityType = activityType,
    userId = userId,
    occurredAt = Instant.now(),
)

class CheckVatThresholdTest : IntegrationTestBase() {
    private fun useCase() = CheckVatThreshold(invoiceRepo, vatAlertRepo, messagingPort)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `80 percent threshold sends alert and records p80SentAt`() =
        runBlocking {
            // Put enough paid revenue to reach 80%
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
                amountCents = P80_AMOUNT,
            )

            useCase().execute(paidEvent(TEST_USER_ID, P80_AMOUNT))

            val messages = messagingPort.allMessages()
            assertEquals(1, messages.size)
            assertTrue(messages.first().text.contains("seuil TVA"))

            val record = vatAlertRepo.findByUserAndYear(TEST_USER_ID, "2026-BIC_SERVICE")
            assertNotNull(record)
            assertNotNull(record.p80SentAt)
            assertNull(record.p95SentAt)
        }

    @Test
    fun `95 percent threshold sends urgent alert and records both p80 and p95`() =
        runBlocking {
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
                amountCents = P95_AMOUNT,
            )

            useCase().execute(paidEvent(TEST_USER_ID, P95_AMOUNT))

            val messages = messagingPort.allMessages()
            assertEquals(1, messages.size)
            assertTrue(messages.first().text.contains("Attention"))

            val record = vatAlertRepo.findByUserAndYear(TEST_USER_ID, "2026-BIC_SERVICE")
            assertNotNull(record)
            assertNotNull(record.p80SentAt)
            assertNotNull(record.p95SentAt)
        }

    @Test
    fun `revenue below 80 percent sends no alert`() =
        runBlocking {
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
                // 1000 euros — well below 80%
                amountCents = 100000L,
            )

            useCase().execute(paidEvent(TEST_USER_ID, 100000L))

            assertTrue(messagingPort.allMessages().isEmpty())
            assertNull(vatAlertRepo.findByUserAndYear(TEST_USER_ID, "2026-BIC_SERVICE"))
        }

    @Test
    fun `already alerted at 80 percent does not send duplicate`() =
        runBlocking {
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
                amountCents = P80_AMOUNT,
            )

            // First call — records p80
            useCase().execute(paidEvent(TEST_USER_ID, P80_AMOUNT))
            messagingPort.clear()

            // Second call at same level — should NOT send again
            useCase().execute(paidEvent(TEST_USER_ID, P80_AMOUNT))

            assertTrue(messagingPort.allMessages().isEmpty())
        }
}
