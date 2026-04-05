package mona.domain.port

import mona.domain.model.UserId

data class IncomingMessage(
    val telegramId: Long,
    val text: String,
    val userId: UserId?,
)

data class IncomingCallback(
    val telegramId: Long,
    val callbackQueryId: String,
    val data: String,
    val userId: UserId?,
)

data class Button(
    val text: String,
    val callbackData: String,
)

data class MenuItem(
    val text: String,
)

interface MessagingPort {
    suspend fun sendMessage(
        userId: UserId,
        text: String,
    )

    suspend fun sendDocument(
        userId: UserId,
        fileBytes: ByteArray,
        fileName: String,
        caption: String?,
    )

    suspend fun sendButtons(
        userId: UserId,
        text: String,
        buttons: List<Button>,
    )

    suspend fun setPersistentMenu(
        userId: UserId,
        items: List<MenuItem>,
    )

    suspend fun onMessage(handler: suspend (IncomingMessage) -> Unit)

    suspend fun onCallback(handler: suspend (IncomingCallback) -> Unit)

    suspend fun answerCallback(
        callbackQueryId: String,
        text: String? = null,
    )
}
