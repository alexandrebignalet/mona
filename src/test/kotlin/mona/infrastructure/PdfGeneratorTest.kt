package mona.infrastructure

import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.ClientId
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
import mona.domain.model.Siren
import mona.domain.model.User
import mona.domain.model.UserId
import mona.infrastructure.pdf.PdfGenerator
import org.apache.pdfbox.Loader
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfGeneratorTest {
    private val userId = UserId("user-1")
    private val clientId = ClientId("client-1")

    private fun testUser() =
        User(
            id = userId,
            telegramId = 12345L,
            email = Email("test@example.com"),
            name = "Jean Dupont",
            siren = Siren("123456789"),
            siret = null,
            address = PostalAddress("12 Rue de la Paix", "75001", "Paris"),
            ibanEncrypted = null,
            activityType = ActivityType.BNC,
            declarationPeriodicity = DeclarationPeriodicity.MONTHLY,
            confirmBeforeCreate = true,
            defaultPaymentDelayDays = PaymentDelayDays(30),
            createdAt = Instant.parse("2026-03-01T10:00:00Z"),
        )

    private fun testClient() =
        Client(
            id = clientId,
            userId = userId,
            name = "Acme SAS",
            email = null,
            address = null,
            companyName = null,
            siret = null,
            createdAt = Instant.parse("2026-03-01T10:00:00Z"),
        )

    private fun testInvoice(): Invoice {
        val lineItems =
            listOf(
                LineItem("Developpement web", BigDecimal("1"), Cents(120000)),
            )
        return when (
            val result =
                Invoice.create(
                    id = InvoiceId("inv-1"),
                    userId = userId,
                    clientId = clientId,
                    number = InvoiceNumber("F-2026-03-001"),
                    issueDate = LocalDate.of(2026, 3, 22),
                    paymentDelay = PaymentDelayDays(30),
                    activityType = ActivityType.BNC,
                    lineItems = lineItems,
                    now = Instant.parse("2026-03-22T10:00:00Z"),
                )
        ) {
            is DomainResult.Ok -> result.value
            is DomainResult.Err -> error("Test invoice creation failed: ${result.error}")
        }
    }

    @Test
    fun `generateInvoice returns non-empty valid PDF`() {
        val pdfBytes = PdfGenerator.generateInvoice(testInvoice(), testUser(), testClient(), null)

        assertTrue(pdfBytes.isNotEmpty())
        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
    }

    @Test
    fun `draft invoice generates valid PDF with watermark code path`() {
        val invoice = testInvoice()
        assertTrue(invoice.status is InvoiceStatus.Draft)

        val pdfBytes = PdfGenerator.generateInvoice(invoice, testUser(), testClient(), null)

        assertTrue(pdfBytes.isNotEmpty())
        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
    }

    @Test
    fun `invoice with IBAN section produces valid PDF`() {
        val pdfBytes =
            PdfGenerator.generateInvoice(
                testInvoice(),
                testUser(),
                testClient(),
                "FR7630006000011234567890189",
            )

        assertTrue(pdfBytes.isNotEmpty())
        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
    }

    @Test
    fun `invoice without user SIREN produces valid PDF`() {
        val userWithoutSiren = testUser().copy(siren = null, address = null)

        val pdfBytes = PdfGenerator.generateInvoice(testInvoice(), userWithoutSiren, testClient(), null)

        assertTrue(pdfBytes.isNotEmpty())
        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
    }

    @Test
    fun `invoice with client address and SIRET produces valid PDF`() {
        val clientWithAddress =
            testClient().copy(
                address = PostalAddress("5 Avenue des Champs", "75008", "Paris"),
                companyName = "Acme SAS",
            )

        val pdfBytes = PdfGenerator.generateInvoice(testInvoice(), testUser(), clientWithAddress, null)

        assertTrue(pdfBytes.isNotEmpty())
        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
    }

    @Test
    fun `paid invoice with payment method produces valid PDF`() {
        val invoice = testInvoice()
        val paidInvoice =
            when (
                val result =
                    invoice.markPaid(
                        LocalDate.of(2026, 4, 1),
                        PaymentMethod.VIREMENT,
                        Instant.parse("2026-04-01T10:00:00Z"),
                    )
            ) {
                is DomainResult.Ok -> result.value.invoice
                is DomainResult.Err -> error("markPaid failed: ${result.error}")
            }

        val pdfBytes = PdfGenerator.generateInvoice(paidInvoice, testUser(), testClient(), null)

        assertTrue(pdfBytes.isNotEmpty())
        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
    }

    @Test
    fun `invoice with multiple line items produces valid PDF`() {
        val lineItems =
            listOf(
                LineItem("Developpement web", BigDecimal("3"), Cents(50000)),
                LineItem("Design UI", BigDecimal("2"), Cents(40000)),
                LineItem("Tests", BigDecimal("1"), Cents(30000)),
            )
        val invoice =
            when (
                val result =
                    Invoice.create(
                        id = InvoiceId("inv-2"),
                        userId = userId,
                        clientId = clientId,
                        number = InvoiceNumber("F-2026-03-002"),
                        issueDate = LocalDate.of(2026, 3, 22),
                        paymentDelay = PaymentDelayDays(30),
                        activityType = ActivityType.BIC_SERVICE,
                        lineItems = lineItems,
                        now = Instant.parse("2026-03-22T10:00:00Z"),
                    )
            ) {
                is DomainResult.Ok -> result.value
                is DomainResult.Err -> error("Invoice creation failed: ${result.error}")
            }

        val pdfBytes = PdfGenerator.generateInvoice(invoice, testUser(), testClient(), null)

        assertTrue(pdfBytes.isNotEmpty())
        Loader.loadPDF(pdfBytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
    }
}
