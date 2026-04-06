package mona.integration.payment

import kotlinx.coroutines.runBlocking
import mona.application.payment.OverdueTransitionJob
import mona.domain.model.DomainEvent
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaymentDelayDays
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val TODAY = LocalDate.of(2026, 4, 6)

class OverdueTransitionJobTest : IntegrationTestBase() {
    private fun useCase() = OverdueTransitionJob(invoiceRepo, eventDispatcher)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `sent invoice 3+ days overdue transitions to Overdue`() =
        runBlocking {
            // issueDate = March 2 + 1 day = due March 3, which is 34 days before April 6 → overdue
            val issueDate = LocalDate.of(2026, 3, 2)
            val invoice =
                createTestInvoice(
                    TEST_USER_ID,
                    TEST_CLIENT_ID,
                    InvoiceStatus.Sent,
                    issueDate = issueDate,
                    paymentDelay = PaymentDelayDays(1),
                )

            useCase().execute(TODAY)

            val saved = invoiceRepo.findById(invoice.id)
            assertIs<InvoiceStatus.Overdue>(saved?.status)
        }

    @Test
    fun `sent invoice 2 days overdue does not transition (threshold is 3)`() =
        runBlocking {
            // issue = April 3 + 1 day delay → due = April 4; cutoff = April 3 → April 4 > April 3 → no transition
            val issueDate = LocalDate.of(2026, 4, 3)
            val invoice =
                createTestInvoice(
                    TEST_USER_ID,
                    TEST_CLIENT_ID,
                    InvoiceStatus.Sent,
                    issueDate = issueDate,
                    paymentDelay = PaymentDelayDays(1),
                )

            useCase().execute(TODAY)

            val saved = invoiceRepo.findById(invoice.id)
            assertIs<InvoiceStatus.Sent>(saved?.status)
        }

    @Test
    fun `already overdue invoice is not reprocessed`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Overdue)

            useCase().execute(TODAY)

            // Still overdue, no duplicate events
            val saved = invoiceRepo.findById(invoice.id)
            assertIs<InvoiceStatus.Overdue>(saved?.status)
        }

    @Test
    fun `multiple overdue invoices all transition and dispatch events`() =
        runBlocking {
            val issueDate = LocalDate.of(2026, 3, 1)
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Sent,
                issueDate = issueDate,
                paymentDelay = PaymentDelayDays(1),
            )
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Sent,
                issueDate = issueDate,
                paymentDelay = PaymentDelayDays(1),
            )

            useCase().execute(TODAY)

            val overdueEvents = eventCollector.events.filterIsInstance<DomainEvent.InvoiceOverdue>()
            assertEquals(2, overdueEvents.size)
        }
}
