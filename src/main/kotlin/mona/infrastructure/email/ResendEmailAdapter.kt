package mona.infrastructure.email

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.port.EmailPort
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.util.Base64

internal sealed class ResendResult {
    data object Success : ResendResult()

    data class Failure(val statusCode: Int, val body: String) : ResendResult()
}

internal fun interface HttpExecutor {
    suspend fun post(
        url: String,
        apiKey: String,
        jsonBody: String,
    ): ResendResult
}

internal class RealHttpExecutor : HttpExecutor {
    private val client: HttpClient = HttpClient.newHttpClient()

    override suspend fun post(
        url: String,
        apiKey: String,
        jsonBody: String,
    ): ResendResult =
        withContext(Dispatchers.IO) {
            val request =
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build()
            val response = client.send(request, BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                ResendResult.Success
            } else {
                ResendResult.Failure(response.statusCode(), response.body())
            }
        }
}

class ResendEmailAdapter internal constructor(
    private val apiKey: String,
    private val senderAddress: String = DEFAULT_SENDER,
    private val httpExecutor: HttpExecutor = RealHttpExecutor(),
) : EmailPort {
    private val log = LoggerFactory.getLogger(ResendEmailAdapter::class.java)

    companion object {
        const val DEFAULT_SENDER = "factures@mona-app.fr"
        private const val RESEND_API_URL = "https://api.resend.com/emails"

        fun fromEnv(senderAddress: String = DEFAULT_SENDER): ResendEmailAdapter =
            ResendEmailAdapter(
                apiKey =
                    System.getenv("RESEND_API_KEY")
                        ?: error("RESEND_API_KEY environment variable is not set"),
                senderAddress = senderAddress,
            )
    }

    override suspend fun sendInvoice(
        to: String,
        subject: String,
        body: String,
        pdfAttachment: ByteArray,
        filename: String,
    ): DomainResult<Unit> {
        val encodedPdf = Base64.getEncoder().encodeToString(pdfAttachment)
        val json =
            buildJsonObject {
                put("from", senderAddress)
                put("to", buildJsonArray { add(JsonPrimitive(to)) })
                put("subject", subject)
                put("html", body)
                put(
                    "attachments",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("filename", filename)
                                put("content", encodedPdf)
                            },
                        )
                    },
                )
            }.toString()

        return when (val result = httpExecutor.post(RESEND_API_URL, apiKey, json)) {
            is ResendResult.Success -> DomainResult.Ok(Unit)
            is ResendResult.Failure -> {
                // L9
                log.warn("Email delivery failed: HTTP {} body={}", result.statusCode, result.body.take(500))
                DomainResult.Err(
                    DomainError.EmailDeliveryFailed(result.statusCode, result.body),
                )
            }
        }
    }
}
