package mona.infrastructure.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val telegramJson = Json { ignoreUnknownKeys = true }

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null,
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TgChat,
    val text: String? = null,
)

@Serializable
data class TgChat(val id: Long)

@Serializable
data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val data: String? = null,
)

@Serializable
data class TgUser(val id: Long)

@Serializable
data class TgResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
)
