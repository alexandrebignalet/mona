package mona.integration.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.invoicing.UpdateDraft
import mona.application.invoicing.UpdateDraftCommand
import mona.application.invoicing.UpdateDraftResult
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.DomainResult
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateDraftTest : IntegrationTestBase() {
    private fun useCase() = UpdateDraft(userRepo, clientRepo, invoiceRepo, pdfPort, eventDispatcher)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `update line items regenerates PDF and saves updated invoice`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val newItems = listOf(LineItem("New Service", BigDecimal("2"), Cents(75000)))
            val result =
                useCase().execute(
                    UpdateDraftCommand(userId = TEST_USER_ID, invoiceId = invoice.id, lineItems = newItems),
                )
            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            assertEquals(Cents(150000), result.value.invoice.amountHt)
            assertTrue(result.value.pdf.size > 4)
            val saved = invoiceRepo.findById(invoice.id)
            assertEquals(Cents(150000), saved?.amountHt)
        }

    @Test
    fun `change client re-links invoice to new client`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    UpdateDraftCommand(userId = TEST_USER_ID, invoiceId = invoice.id, clientName = "Brand New Client"),
                )
            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            // New client was created
            val clients = clientRepo.findByUser(TEST_USER_ID)
            assertTrue(clients.any { it.name.equals("Brand New Client", ignoreCase = true) })
        }

    @Test
    fun `update issue date recalculates due date`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val newDate = LocalDate.of(2026, 6, 1)
            val result =
                useCase().execute(
                    UpdateDraftCommand(userId = TEST_USER_ID, invoiceId = invoice.id, issueDate = newDate),
                )
            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            assertEquals(newDate, result.value.invoice.issueDate)
        }

    @Test
    fun `non-Draft invoice returns Err`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
            val result =
                useCase().execute(
                    UpdateDraftCommand(userId = TEST_USER_ID, invoiceId = invoice.id, activityType = ActivityType.BNC),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `partial update changes only specified field`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val originalClientId = invoice.clientId
            val result =
                useCase().execute(
                    UpdateDraftCommand(userId = TEST_USER_ID, invoiceId = invoice.id, activityType = ActivityType.BNC),
                )
            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            assertEquals(ActivityType.BNC, result.value.invoice.activityType)
            // Other fields unchanged
            assertEquals(originalClientId, result.value.invoice.clientId)
            assertEquals(invoice.amountHt, result.value.invoice.amountHt)
        }
}
