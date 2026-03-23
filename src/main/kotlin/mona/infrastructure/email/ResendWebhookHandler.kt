package mona.infrastructure.email

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mona.application.invoicing.HandleBouncedEmail
import mona.domain.model.InvoiceNumber
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val INVOICE_NUMBER_REGEX = Regex("F-\\d{4}-\\d{2}-\\d{3,}")

internal fun interface BounceProcessor {
    suspend fun process(
        invoiceNumber: InvoiceNumber,
        recipientEmail: String,
    )
}

class ResendWebhookHandler internal constructor(
    private val signingSecret: String,
    private val processor: BounceProcessor,
    private val scope: CoroutineScope,
) {
    companion object {
        operator fun invoke(
            signingSecret: String,
            handleBouncedEmail: HandleBouncedEmail,
            scope: CoroutineScope,
        ): ResendWebhookHandler =
            ResendWebhookHandler(
                signingSecret = signingSecret,
                processor = BounceProcessor { number, email -> handleBouncedEmail.execute(number, email) },
                scope = scope,
            )
    }

    fun handle(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.bufferedReader().readText()
            val svixId = exchange.requestHeaders.getFirst("svix-id") ?: ""
            val svixTimestamp = exchange.requestHeaders.getFirst("svix-timestamp") ?: ""
            val svixSignature = exchange.requestHeaders.getFirst("svix-signature") ?: ""

            if (signingSecret.isNotEmpty() && !verify(svixId, svixTimestamp, body, svixSignature)) {
                respond(exchange, 400)
                return
            }

            val json =
                runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
                    respond(exchange, 400)
                    return
                }

            respond(exchange, 200)

            val type = json["type"]?.jsonPrimitive?.contentOrNull
            if (type == "email.bounced") {
                val data = json["data"]?.jsonObject ?: return
                val subject = data["subject"]?.jsonPrimitive?.contentOrNull ?: return
                val recipient =
                    data["to"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: return
                val numberStr = INVOICE_NUMBER_REGEX.find(subject)?.value ?: return
                scope.launch {
                    processor.process(InvoiceNumber(numberStr), recipient)
                }
            }
        } catch (_: Exception) {
            runCatching { respond(exchange, 500) }
        }
    }

    private fun verify(
        svixId: String,
        svixTimestamp: String,
        body: String,
        sigHeader: String,
    ): Boolean {
        val secretBytes =
            runCatching {
                Base64.getDecoder().decode(signingSecret.removePrefix("whsec_"))
            }.getOrElse { return false }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        val signed = "$svixId.$svixTimestamp.$body"
        val computed = Base64.getEncoder().encodeToString(mac.doFinal(signed.toByteArray(Charsets.UTF_8)))
        return sigHeader.split(" ").any { sig -> sig.removePrefix("v1,") == computed }
    }

    private fun respond(
        exchange: HttpExchange,
        code: Int,
    ) {
        exchange.sendResponseHeaders(code, 0)
        exchange.responseBody.close()
    }
}
