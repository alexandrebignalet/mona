package mona.integration.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.invoicing.CreateInvoice
import mona.application.invoicing.CreateInvoiceCommand
import mona.application.invoicing.CreateInvoiceResult
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.DomainResult
import mona.domain.model.InvoiceNumber
import mona.domain.model.LineItem
import mona.domain.model.PaymentDelayDays
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val PDF_HEADER = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
private val ISSUE_DATE = LocalDate.of(2026, 4, 1)

class CreateInvoiceTest : IntegrationTestBase() {
    private fun useCase() = CreateInvoice(userRepo, clientRepo, invoiceRepo, pdfPort, eventDispatcher)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    private fun cmd(
        clientName: String = "ACME Corp",
        items: List<LineItem> = listOf(LineItem("Conseil", BigDecimal.ONE, Cents(50000))),
        date: LocalDate = ISSUE_DATE,
        activityType: ActivityType = ActivityType.BIC_SERVICE,
        paymentDelay: PaymentDelayDays = PaymentDelayDays(30),
    ) = CreateInvoiceCommand(
        userId = TEST_USER_ID,
        clientName = clientName,
        lineItems = items,
        issueDate = date,
        activityType = activityType,
        paymentDelay = paymentDelay,
    )

    @Test
    fun `happy path creates draft invoice with sequential number and real PDF`() =
        runBlocking {
            val result = useCase().execute(cmd(clientName = "New Client"))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(result)
            val created = assertIs<CreateInvoiceResult.Created>(result.value)
            assertEquals(InvoiceNumber("F-2026-04-001"), created.invoice.number)
            assertTrue(created.pdf.size > 4)
            assertTrue(created.pdf.take(4).toByteArray().contentEquals(PDF_HEADER))
            assertNotNull(invoiceRepo.findById(created.invoice.id))
        }

    @Test
    fun `existing client is reused, no duplicate created`() =
        runBlocking {
            // "ACME Corp" matches testClient's name via LIKE
            val result = useCase().execute(cmd(clientName = "ACME Corp"))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(result)
            val created = assertIs<CreateInvoiceResult.Created>(result.value)
            assertEquals(TEST_CLIENT_ID, created.invoice.clientId)
        }

    @Test
    fun `multiple line items total is sum of all items`() =
        runBlocking {
            val items =
                listOf(
                    LineItem("Dev", BigDecimal("2"), Cents(30000)),
                    LineItem("Design", BigDecimal("1"), Cents(20000)),
                    LineItem("Conseil", BigDecimal("3"), Cents(10000)),
                )
            val result = useCase().execute(cmd(clientName = "Multi Client", items = items))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(result)
            val created = assertIs<CreateInvoiceResult.Created>(result.value)
            // Total = (2*30000) + (1*20000) + (3*10000) = 60000+20000+30000 = 110000
            assertEquals(Cents(110000), created.invoice.amountHt)
        }

    @Test
    fun `duplicate warning when same client and amount within 2 days`() =
        runBlocking {
            // First invoice
            val r1 = useCase().execute(cmd(clientName = "ACME Corp"))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r1)
            // Second identical invoice
            val r2 = useCase().execute(cmd(clientName = "ACME Corp"))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r2)
            assertIs<CreateInvoiceResult.DuplicateWarning>(r2.value)
        }

    @Test
    fun `sequential numbering assigns 001 then 002 in same month`() =
        runBlocking {
            val r1 = useCase().execute(cmd(clientName = "Client A", items = listOf(LineItem("A", BigDecimal.ONE, Cents(10000)))))
            val r2 = useCase().execute(cmd(clientName = "Client B", items = listOf(LineItem("B", BigDecimal.ONE, Cents(20000)))))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r1)
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r2)
            assertEquals(InvoiceNumber("F-2026-04-001"), (r1.value as CreateInvoiceResult.Created).invoice.number)
            assertEquals(InvoiceNumber("F-2026-04-002"), (r2.value as CreateInvoiceResult.Created).invoice.number)
        }

    @Test
    fun `numbering resets to 001 in new month`() =
        runBlocking {
            val janDate = LocalDate.of(2026, 1, 15)
            val febDate = LocalDate.of(2026, 2, 15)
            val r1 = useCase().execute(cmd(clientName = "X", date = janDate))
            val r2 = useCase().execute(cmd(clientName = "Y", date = febDate))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r1)
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r2)
            assertEquals(InvoiceNumber("F-2026-01-001"), (r1.value as CreateInvoiceResult.Created).invoice.number)
            assertEquals(InvoiceNumber("F-2026-02-001"), (r2.value as CreateInvoiceResult.Created).invoice.number)
        }

    @Test
    fun `custom payment delay is stored on invoice`() =
        runBlocking {
            val result = useCase().execute(cmd(clientName = "Custom Delay", paymentDelay = PaymentDelayDays(60)))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(result)
            val invoice = (result.value as CreateInvoiceResult.Created).invoice
            assertEquals(ISSUE_DATE.plusDays(60), invoice.dueDate)
        }

    @Test
    fun `activity type override is stored on invoice`() =
        runBlocking {
            val result = useCase().execute(cmd(clientName = "Override", activityType = ActivityType.BIC_VENTE))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(result)
            val invoice = (result.value as CreateInvoiceResult.Created).invoice
            assertEquals(ActivityType.BIC_VENTE, invoice.activityType)
        }
}
