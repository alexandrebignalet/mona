package mona.infrastructure.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.UUID

internal data class TelegramHttpResponse(val statusCode: Int, val body: String)

internal sealed class MultipartField {
    data class Text(val name: String, val value: String) : MultipartField()

    class File(
        val name: String,
        val fileName: String,
        val bytes: ByteArray,
        val contentType: String = "application/octet-stream",
    ) : MultipartField()
}

internal interface TelegramHttpExecutor {
    suspend fun post(
        method: String,
        bodyJson: String,
    ): TelegramHttpResponse

    suspend fun postMultipart(
        method: String,
        parts: List<MultipartField>,
    ): TelegramHttpResponse
}

internal class RealTelegramHttpExecutor(private val baseUrl: String) : TelegramHttpExecutor {
    private val client: HttpClient = HttpClient.newHttpClient()

    override suspend fun post(
        method: String,
        bodyJson: String,
    ): TelegramHttpResponse =
        withContext(Dispatchers.IO) {
            val request =
                HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/$method"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(bodyJson))
                    .build()
            val response = client.send(request, BodyHandlers.ofString())
            TelegramHttpResponse(response.statusCode(), response.body())
        }

    override suspend fun postMultipart(
        method: String,
        parts: List<MultipartField>,
    ): TelegramHttpResponse =
        withContext(Dispatchers.IO) {
            val boundary = UUID.randomUUID().toString().replace("-", "")
            val body = buildMultipartBody(boundary, parts)
            val request =
                HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/$method"))
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(BodyPublishers.ofByteArray(body))
                    .build()
            val response = client.send(request, BodyHandlers.ofString())
            TelegramHttpResponse(response.statusCode(), response.body())
        }

    private fun buildMultipartBody(
        boundary: String,
        parts: List<MultipartField>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val charset = Charsets.UTF_8
        for (part in parts) {
            baos.write("--$boundary\r\n".toByteArray(charset))
            when (part) {
                is MultipartField.Text -> {
                    baos.write("Content-Disposition: form-data; name=\"${part.name}\"\r\n\r\n".toByteArray(charset))
                    baos.write(part.value.toByteArray(charset))
                }
                is MultipartField.File -> {
                    baos.write(
                        "Content-Disposition: form-data; name=\"${part.name}\"; filename=\"${part.fileName}\"\r\n"
                            .toByteArray(charset),
                    )
                    baos.write("Content-Type: ${part.contentType}\r\n\r\n".toByteArray(charset))
                    baos.write(part.bytes)
                }
            }
            baos.write("\r\n".toByteArray(charset))
        }
        baos.write("--$boundary--\r\n".toByteArray(charset))
        return baos.toByteArray()
    }
}

sealed class TgResult<out T> {
    data class Ok<T>(val value: T) : TgResult<T>()

    data class Err(val code: Int?, val description: String) : TgResult<Nothing>()
}

class TelegramApiClient internal constructor(
    private val token: String,
    private val executor: TelegramHttpExecutor =
        RealTelegramHttpExecutor("https://api.telegram.org/bot$token"),
) {
    companion object {
        fun create(token: String): TelegramApiClient = TelegramApiClient(token)
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyMarkup: JsonElement? = null,
    ): TgResult<TgMessage> {
        val body =
            buildJsonObject {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "Markdown")
                if (replyMarkup != null && replyMarkup != JsonNull) {
                    put("reply_markup", replyMarkup)
                }
            }.toString()
        return decode(executor.post("sendMessage", body)) { json ->
            telegramJson.decodeFromJsonElement(TgMessage.serializer(), json)
        }
    }

    suspend fun sendDocument(
        chatId: Long,
        fileBytes: ByteArray,
        fileName: String,
        caption: String?,
    ): TgResult<TgMessage> {
        val parts =
            buildList {
                add(MultipartField.Text("chat_id", chatId.toString()))
                if (caption != null) add(MultipartField.Text("caption", caption))
                add(MultipartField.File("document", fileName, fileBytes))
            }
        return decode(executor.postMultipart("sendDocument", parts)) { json ->
            telegramJson.decodeFromJsonElement(TgMessage.serializer(), json)
        }
    }

    suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String? = null,
    ): TgResult<Boolean> {
        val body =
            buildJsonObject {
                put("callback_query_id", callbackQueryId)
                if (text != null) put("text", text)
            }.toString()
        return decode(executor.post("answerCallbackQuery", body)) { json ->
            json.jsonPrimitive.boolean
        }
    }

    suspend fun setWebhook(
        url: String,
        secretToken: String,
        allowedUpdates: List<String>,
    ): TgResult<Boolean> {
        val body =
            buildJsonObject {
                put("url", url)
                put("secret_token", secretToken)
                put(
                    "allowed_updates",
                    buildJsonArray { allowedUpdates.forEach { add(it) } },
                )
            }.toString()
        return decode(executor.post("setWebhook", body)) { json ->
            json.jsonPrimitive.boolean
        }
    }

    suspend fun deleteWebhook(): TgResult<Boolean> {
        val body = buildJsonObject {}.toString()
        return decode(executor.post("deleteWebhook", body)) { json ->
            json.jsonPrimitive.boolean
        }
    }

    private fun <T> decode(
        response: TelegramHttpResponse,
        deserialize: (JsonElement) -> T,
    ): TgResult<T> =
        try {
            val root = telegramJson.parseToJsonElement(response.body).jsonObject
            val ok = root["ok"]?.jsonPrimitive?.boolean ?: false
            val errorCode = root["error_code"]?.jsonPrimitive?.content?.toIntOrNull()
            val description = root["description"]?.jsonPrimitive?.content
            val result = root["result"]
            if (ok && result != null && result != JsonNull) {
                TgResult.Ok(deserialize(result))
            } else {
                TgResult.Err(errorCode, description ?: "Unknown error")
            }
        } catch (e: Exception) {
            TgResult.Err(null, "Parse error: ${e.message}")
        }
}
