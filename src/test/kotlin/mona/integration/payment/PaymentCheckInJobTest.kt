package mona.integration.payment

import kotlinx.coroutines.runBlocking
import mona.application.payment.PaymentCheckInJob
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaymentDelayDays
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TODAY = LocalDate.of(2026, 4, 6)

class PaymentCheckInJobTest : IntegrationTestBase() {
    private fun useCase() = PaymentCheckInJob(invoiceRepo, clientRepo, messagingPort)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `invoice due yesterday triggers check-in message`() =
        runBlocking {
            // issue date + 30 day delay = due on April 4, which is yesterday relative to TODAY (April 6) — no
            // issue date = March 6, delay = 30 days → due March 36 = April 5 = yesterday relative to April 6
            val issueDate = LocalDate.of(2026, 3, 6)
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Sent,
                issueDate = issueDate,
                paymentDelay = PaymentDelayDays(30),
            )
            // due date = March 6 + 30 = April 5 = yesterday (TODAY = April 6)

            useCase().execute(TODAY)

            val messages = messagingPort.allMessages()
            assertEquals(1, messages.size)
            assertTrue(messages.first().text.contains("devait être payée hier"))
        }

    @Test
    fun `no invoices due yesterday sends no messages`() =
        runBlocking {
            useCase().execute(TODAY)
            assertTrue(messagingPort.allMessages().isEmpty())
        }

    @Test
    fun `multiple invoices due yesterday produces one message with list`() =
        runBlocking {
            val issueDate = LocalDate.of(2026, 3, 6)
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Sent,
                issueDate = issueDate,
                paymentDelay = PaymentDelayDays(30),
            )
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Sent,
                issueDate = issueDate,
                paymentDelay = PaymentDelayDays(30),
            )

            useCase().execute(TODAY)

            val messages = messagingPort.allMessages()
            // One message per user, with multiple invoices listed
            assertEquals(1, messages.size)
            assertTrue(messages.first().text.contains("factures arrivaient à échéance hier"))
        }
}
