package mona.integration.payment

import kotlinx.coroutines.runBlocking
import mona.application.invoicing.MarkInvoicePaid
import mona.application.invoicing.MarkInvoicePaidCommand
import mona.domain.model.DomainEvent
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaymentMethod
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkPaidTest : IntegrationTestBase() {
    private fun useCase() = MarkInvoicePaid(invoiceRepo, eventDispatcher)

    private val paymentDate = LocalDate.of(2026, 4, 15)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `Sent invoice transitions to Paid and dispatches InvoicePaid event`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
            val result =
                useCase().execute(
                    MarkInvoicePaidCommand(TEST_USER_ID, invoice.id, paymentDate, PaymentMethod.VIREMENT),
                )
            assertIs<DomainResult.Ok<Invoice>>(result)
            assertIs<InvoiceStatus.Paid>(result.value.status)
            val paid = result.value.status as InvoiceStatus.Paid
            assertEquals(paymentDate, paid.date)
            assertEquals(PaymentMethod.VIREMENT, paid.method)
            assertTrue(eventCollector.events.any { it is DomainEvent.InvoicePaid })
            // Persisted
            val saved = invoiceRepo.findById(invoice.id)
            assertIs<InvoiceStatus.Paid>(saved?.status)
        }

    @Test
    fun `Overdue invoice transitions to Paid`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Overdue)
            val result =
                useCase().execute(
                    MarkInvoicePaidCommand(TEST_USER_ID, invoice.id, paymentDate, PaymentMethod.CHEQUE),
                )
            assertIs<DomainResult.Ok<Invoice>>(result)
            assertIs<InvoiceStatus.Paid>(result.value.status)
        }

    @Test
    fun `Draft invoice returns Err`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    MarkInvoicePaidCommand(TEST_USER_ID, invoice.id, paymentDate, PaymentMethod.VIREMENT),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `already Paid invoice returns Err`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Paid(paymentDate, PaymentMethod.VIREMENT))
            val result =
                useCase().execute(
                    MarkInvoicePaidCommand(TEST_USER_ID, invoice.id, paymentDate, PaymentMethod.VIREMENT),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `all payment methods are accepted`() =
        runBlocking {
            for (method in listOf(PaymentMethod.CHEQUE, PaymentMethod.ESPECES, PaymentMethod.CARTE, PaymentMethod.AUTRE)) {
                val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
                val result = useCase().execute(MarkInvoicePaidCommand(TEST_USER_ID, invoice.id, paymentDate, method))
                assertIs<DomainResult.Ok<Invoice>>(result)
                val paid = result.value.status as InvoiceStatus.Paid
                assertEquals(method, paid.method)
            }
        }
}
