package mona.integration.onboarding

import kotlinx.coroutines.runBlocking
import mona.application.onboarding.FinalizeInvoice
import mona.application.onboarding.FinalizeInvoiceCommand
import mona.application.onboarding.FinalizeInvoiceResult
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.InvoiceStatus
import mona.integration.IntegrationTestBase
import mona.integration.TEST_IBAN_PLAIN
import mona.integration.TEST_USER_ID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val PDF_HEADER = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF

class FinalizeInvoiceTest : IntegrationTestBase() {
    private fun useCase() = FinalizeInvoice(userRepo, clientRepo, invoiceRepo, pdfPort)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `generates real PDF with valid header when IBAN provided`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, mona.integration.TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    FinalizeInvoiceCommand(userId = TEST_USER_ID, invoiceId = invoice.id, plainIban = TEST_IBAN_PLAIN),
                )
            assertIs<DomainResult.Ok<FinalizeInvoiceResult>>(result)
            val pdf = result.value.pdf
            assertTrue(pdf.size > 4)
            assertTrue(pdf.take(4).toByteArray().contentEquals(PDF_HEADER), "PDF must start with %PDF header")
        }

    @Test
    fun `generates real PDF without IBAN`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, mona.integration.TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    FinalizeInvoiceCommand(userId = TEST_USER_ID, invoiceId = invoice.id, plainIban = null),
                )
            assertIs<DomainResult.Ok<FinalizeInvoiceResult>>(result)
            assertTrue(result.value.pdf.size > 4)
        }

    @Test
    fun `returns SirenRequired when user has no SIREN`() =
        runBlocking {
            val minimalUser = createTestUserMinimal()
            val invoice = createTestInvoice(minimalUser.id, mona.integration.TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    FinalizeInvoiceCommand(userId = minimalUser.id, invoiceId = invoice.id, plainIban = null),
                )
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SirenRequired>(result.error)
        }
}
