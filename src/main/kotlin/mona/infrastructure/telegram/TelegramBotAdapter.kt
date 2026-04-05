package mona.infrastructure.telegram

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mona.domain.model.UserId
import mona.domain.port.Button
import mona.domain.port.IncomingCallback
import mona.domain.port.IncomingMessage
import mona.domain.port.MenuItem
import mona.domain.port.MessagingPort
import mona.domain.port.UserRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TelegramBotAdapter(
    private val apiClient: TelegramApiClient,
    private val userRepository: UserRepository,
    private val webhookUrl: String,
    private val webhookSecret: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MessagingPort {
    private val messageHandlers = CopyOnWriteArrayList<suspend (IncomingMessage) -> Unit>()
    private val callbackHandlers = CopyOnWriteArrayList<suspend (IncomingCallback) -> Unit>()
    private val chatIdCache = ConcurrentHashMap<UserId, Long>()
    private val persistentKeyboards = ConcurrentHashMap<UserId, kotlinx.serialization.json.JsonElement>()

    override suspend fun sendMessage(
        userId: UserId,
        text: String,
    ) {
        val chatId = resolveChatId(userId) ?: return
        val keyboard = persistentKeyboards[userId]
        apiClient.sendMessage(chatId, text, keyboard)
    }

    override suspend fun sendDocument(
        userId: UserId,
        fileBytes: ByteArray,
        fileName: String,
        caption: String?,
    ) {
        val chatId = resolveChatId(userId) ?: return
        apiClient.sendDocument(chatId, fileBytes, fileName, caption)
    }

    override suspend fun sendButtons(
        userId: UserId,
        text: String,
        buttons: List<Button>,
    ) {
        val chatId = resolveChatId(userId) ?: return
        val inlineKeyboard =
            buildJsonObject {
                put(
                    "inline_keyboard",
                    buildJsonArray {
                        add(
                            buildJsonArray {
                                buttons.forEach { button ->
                                    add(
                                        buildJsonObject {
                                            put("text", button.text)
                                            put("callback_data", button.callbackData)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        apiClient.sendMessage(chatId, text, inlineKeyboard)
    }

    override suspend fun setPersistentMenu(
        userId: UserId,
        items: List<MenuItem>,
    ) {
        val keyboard =
            buildJsonObject {
                put(
                    "keyboard",
                    buildJsonArray {
                        add(
                            buildJsonArray {
                                items.forEach { item -> add(item.text) }
                            },
                        )
                    },
                )
                put("resize_keyboard", true)
                put("is_persistent", true)
            }
        persistentKeyboards[userId] = keyboard
    }

    override suspend fun onMessage(handler: suspend (IncomingMessage) -> Unit) {
        messageHandlers.add(handler)
    }

    override suspend fun onCallback(handler: suspend (IncomingCallback) -> Unit) {
        callbackHandlers.add(handler)
    }

    override suspend fun answerCallback(
        callbackQueryId: String,
        text: String?,
    ) {
        apiClient.answerCallbackQuery(callbackQueryId, text)
    }

    fun handleWebhook(exchange: HttpExchange) {
        try {
            val secretHeader = exchange.requestHeaders.getFirst("X-Telegram-Bot-Api-Secret-Token")
            if (secretHeader != webhookSecret) {
                exchange.sendResponseHeaders(401, 0)
                exchange.responseBody.close()
                return
            }

            val body = exchange.requestBody.bufferedReader().readText()

            // Respond 200 immediately — Telegram retries on non-2xx
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()

            val update =
                runCatching {
                    telegramJson.decodeFromString(TgUpdate.serializer(), body)
                }.getOrNull() ?: return

            scope.launch {
                update.message?.let { message ->
                    val text = message.text ?: return@let
                    val telegramId = message.chat.id
                    val user = userRepository.findByTelegramId(telegramId)
                    user?.let { chatIdCache[it.id] = telegramId }
                    val incoming =
                        IncomingMessage(
                            telegramId = telegramId,
                            text = text,
                            userId = user?.id,
                        )
                    messageHandlers.forEach { it(incoming) }
                }
                update.callbackQuery?.let { callbackQuery ->
                    val data = callbackQuery.data ?: return@let
                    val telegramId = callbackQuery.from.id
                    val user = userRepository.findByTelegramId(telegramId)
                    user?.let { chatIdCache[it.id] = telegramId }
                    val incoming =
                        IncomingCallback(
                            telegramId = telegramId,
                            callbackQueryId = callbackQuery.id,
                            data = data,
                            userId = user?.id,
                        )
                    callbackHandlers.forEach { it(incoming) }
                }
            }
        } catch (_: Exception) {
            runCatching {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
            }
        }
    }

    suspend fun start() {
        apiClient.setWebhook(webhookUrl, webhookSecret, listOf("message", "callback_query"))
    }

    suspend fun stop() {
        apiClient.deleteWebhook()
        scope.cancel()
    }

    private suspend fun resolveChatId(userId: UserId): Long? =
        chatIdCache[userId] ?: userRepository.findById(userId)?.telegramId?.also { telegramId ->
            chatIdCache[userId] = telegramId
        }
}
