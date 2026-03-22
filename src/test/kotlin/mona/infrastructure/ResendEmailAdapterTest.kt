package mona.infrastructure

import kotlinx.coroutines.test.runTest
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.infrastructure.email.ResendEmailAdapter
import mona.infrastructure.email.ResendResult
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResendEmailAdapterTest {
    private val testPdf = ByteArray(16) { it.toByte() }

    private fun adapterWith(executor: suspend (String, String, String) -> ResendResult): ResendEmailAdapter =
        ResendEmailAdapter(
            apiKey = "test-key",
            httpExecutor = { url, apiKey, body -> executor(url, apiKey, body) },
        )

    @Test
    fun `sendInvoice returns Ok when Resend responds 200`() =
        runTest {
            val adapter = adapterWith { _, _, _ -> ResendResult.Success }
            val result = adapter.sendInvoice("client@example.com", "Facture F-2025-01-001", "<p>Bonjour</p>", testPdf, "facture.pdf")
            assertIs<DomainResult.Ok<Unit>>(result)
        }

    @Test
    fun `sendInvoice returns Err with EmailDeliveryFailed on HTTP 422`() =
        runTest {
            val adapter = adapterWith { _, _, _ -> ResendResult.Failure(422, """{"name":"validation_error"}""") }
            val result = adapter.sendInvoice("bad@", "Facture", "<p>body</p>", testPdf, "facture.pdf")
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.EmailDeliveryFailed>(result.error)
            assertEquals(422, (result.error as DomainError.EmailDeliveryFailed).statusCode)
        }

    @Test
    fun `sendInvoice returns Err with EmailDeliveryFailed on HTTP 500`() =
        runTest {
            val adapter = adapterWith { _, _, _ -> ResendResult.Failure(500, "Internal Server Error") }
            val result = adapter.sendInvoice("client@example.com", "Facture", "<p>body</p>", testPdf, "facture.pdf")
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.EmailDeliveryFailed>(result.error)
            assertEquals(500, (result.error as DomainError.EmailDeliveryFailed).statusCode)
        }

    @Test
    fun `sendInvoice encodes PDF as base64 in request body`() =
        runTest {
            var capturedBody: String? = null
            val adapter =
                adapterWith { _, _, body ->
                    capturedBody = body
                    ResendResult.Success
                }
            val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
            adapter.sendInvoice("client@example.com", "Test", "<p>body</p>", pdfBytes, "facture.pdf")
            val expectedBase64 = Base64.getEncoder().encodeToString(pdfBytes)
            assertTrue(capturedBody!!.contains(expectedBase64), "JSON body must contain base64-encoded PDF")
        }

    @Test
    fun `sendInvoice sends to correct recipient and uses default sender`() =
        runTest {
            var capturedBody: String? = null
            val adapter =
                adapterWith { _, _, body ->
                    capturedBody = body
                    ResendResult.Success
                }
            adapter.sendInvoice("recipient@example.com", "Sujet", "<p>body</p>", testPdf, "facture.pdf")
            assertTrue(capturedBody!!.contains("recipient@example.com"))
            assertTrue(capturedBody!!.contains(ResendEmailAdapter.DEFAULT_SENDER))
        }

    @Test
    fun `sendInvoice uses custom sender address when configured`() =
        runTest {
            var capturedBody: String? = null
            val adapter =
                ResendEmailAdapter(
                    apiKey = "key",
                    senderAddress = "custom@mycompany.fr",
                    httpExecutor = { _, _, body ->
                        capturedBody = body
                        ResendResult.Success
                    },
                )
            adapter.sendInvoice("client@example.com", "Facture", "<p>body</p>", testPdf, "facture.pdf")
            assertTrue(capturedBody!!.contains("custom@mycompany.fr"))
        }

    @Test
    fun `sendInvoice passes API key in Authorization header`() =
        runTest {
            var capturedApiKey: String? = null
            val adapter =
                adapterWith { _, apiKey, _ ->
                    capturedApiKey = apiKey
                    ResendResult.Success
                }
            adapter.sendInvoice("client@example.com", "Facture", "<p>body</p>", testPdf, "facture.pdf")
            assertEquals("test-key", capturedApiKey)
        }
}
