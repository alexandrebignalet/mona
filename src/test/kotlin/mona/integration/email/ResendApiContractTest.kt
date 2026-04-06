package mona.integration.email

import kotlinx.coroutines.runBlocking
import mona.domain.model.DomainResult
import mona.infrastructure.email.ResendEmailAdapter
import mona.integration.FakeResendHttpExecutor
import mona.integration.ResendScenario
import kotlin.test.Test
import kotlin.test.assertIs

class ResendApiContractTest {
    private val fakeExecutor = FakeResendHttpExecutor()
    private val adapter = ResendEmailAdapter(apiKey = "test-key", httpExecutor = fakeExecutor)

    private val fakePdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-

    @Test
    fun `success scenario returns Ok`() =
        runBlocking {
            fakeExecutor.scenario = ResendScenario.Success
            val result =
                adapter.sendInvoice(
                    to = "client@example.com",
                    subject = "Facture F-2026-04-001",
                    body = "<p>Bonjour</p>",
                    pdfAttachment = fakePdf,
                    filename = "F-2026-04-001.pdf",
                )
            assertIs<DomainResult.Ok<Unit>>(result)
        }

    @Test
    fun `rate limited response returns Err`() =
        runBlocking {
            fakeExecutor.scenario = ResendScenario.RateLimited
            val result =
                adapter.sendInvoice(
                    to = "client@example.com",
                    subject = "Test",
                    body = "<p>Test</p>",
                    pdfAttachment = fakePdf,
                    filename = "test.pdf",
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `invalid email address returns Err`() =
        runBlocking {
            fakeExecutor.scenario = ResendScenario.InvalidEmail
            val result =
                adapter.sendInvoice(
                    to = "not-an-email",
                    subject = "Test",
                    body = "<p>Test</p>",
                    pdfAttachment = fakePdf,
                    filename = "test.pdf",
                )
            assertIs<DomainResult.Err>(result)
        }
}
