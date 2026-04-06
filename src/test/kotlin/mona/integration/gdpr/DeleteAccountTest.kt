package mona.integration.gdpr

import kotlinx.coroutines.runBlocking
import mona.application.gdpr.DeleteAccount
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaymentMethod
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import mona.integration.TEST_USER_MINIMAL_ID
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeleteAccountTest : IntegrationTestBase() {
    private fun useCase() = DeleteAccount(userRepo, clientRepo, conversationRepo, invoiceRepo)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `full deletion removes user, clients, and anonymizes invoices`() =
        runBlocking {
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Paid(LocalDate.of(2026, 4, 11), PaymentMethod.VIREMENT))

            useCase().execute(TEST_USER_ID)

            // User deleted
            assertNull(userRepo.findById(TEST_USER_ID))
            // Clients deleted
            assertTrue(clientRepo.findByUser(TEST_USER_ID).isEmpty())
            // Invoices anonymized (userId=null) so findByUser returns empty
            assertTrue(invoiceRepo.findByUser(TEST_USER_ID).isEmpty())
        }

    @Test
    fun `user with no data is deleted cleanly`() =
        runBlocking {
            createTestUserMinimal()
            useCase().execute(TEST_USER_MINIMAL_ID)
            assertNull(userRepo.findById(TEST_USER_MINIMAL_ID))
        }

    @Test
    fun `invoice records remain in DB after anonymization (legal preservation)`() =
        runBlocking {
            val invoice =
                createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Paid(LocalDate.of(2026, 4, 11), PaymentMethod.VIREMENT))

            useCase().execute(TEST_USER_ID)

            // Invoice still findable by its primary key
            val found = invoiceRepo.findById(invoice.id)
            assertTrue(found != null, "Invoice should be preserved (not deleted)")
            // But userId and clientId are nulled
            assertNull(found?.userId)
            assertNull(found?.clientId)
        }
}
