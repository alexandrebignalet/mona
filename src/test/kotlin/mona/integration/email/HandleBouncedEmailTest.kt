package mona.integration.email

import kotlinx.coroutines.runBlocking
import mona.application.invoicing.HandleBouncedEmail
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HandleBouncedEmailTest : IntegrationTestBase() {
    private fun useCase() = HandleBouncedEmail(invoiceRepo, messagingPort)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `bounced email on sent invoice reverts to Draft and notifies user`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)

            useCase().execute(invoice.number, "client@example.com")

            val saved = invoiceRepo.findById(invoice.id)
            assertNotNull(saved)
            assertIs<InvoiceStatus.Draft>(saved.status)

            val messages = messagingPort.allMessages()
            assertTrue(messages.size == 1)
            assertTrue(messages.first().text.contains("échoué"))
            assertTrue(messages.first().text.contains(invoice.number.value))
        }

    @Test
    fun `bounced email for unknown invoice number does nothing`() =
        runBlocking {
            useCase().execute(InvoiceNumber("F-2099-01-999"), "ghost@example.com")

            assertTrue(messagingPort.allMessages().isEmpty())
        }
}
