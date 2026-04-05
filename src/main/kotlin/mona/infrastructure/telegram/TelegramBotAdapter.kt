package mona.infrastructure.telegram

import dev.inmo.tgbotapi.bot.RequestsExecutor
import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.telegramBotWithBehaviourAndLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.SimpleKeyboardButton
import dev.inmo.tgbotapi.types.toChatId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val token: String,
    private val userRepository: UserRepository,
    private val coroutineScope: CoroutineScope,
) : MessagingPort {
    private val messageHandlers = CopyOnWriteArrayList<suspend (IncomingMessage) -> Unit>()
    private val callbackHandlers = CopyOnWriteArrayList<suspend (IncomingCallback) -> Unit>()
    private val chatIdCache = ConcurrentHashMap<UserId, Long>()
    private val persistentKeyboards = ConcurrentHashMap<UserId, ReplyKeyboardMarkup>()

    @Volatile
    private var executor: RequestsExecutor? = null

    override suspend fun sendMessage(
        userId: UserId,
        text: String,
    ) {
        val chatId = resolveChatId(userId) ?: return
        val keyboard = persistentKeyboards[userId]
        executor?.sendTextMessage(chatId.toChatId(), text, replyMarkup = keyboard)
    }

    override suspend fun sendDocument(
        userId: UserId,
        fileBytes: ByteArray,
        fileName: String,
        caption: String?,
    ) {
        val chatId = resolveChatId(userId) ?: return
        val multipart = fileBytes.asMultipartFile(fileName)
        executor?.sendDocument(chatId.toChatId(), multipart, text = caption)
    }

    override suspend fun sendButtons(
        userId: UserId,
        text: String,
        buttons: List<Button>,
    ) {
        val chatId = resolveChatId(userId) ?: return
        val keyboard =
            InlineKeyboardMarkup(
                listOf(buttons.map { CallbackDataInlineKeyboardButton(it.text, it.callbackData) }),
            )
        executor?.sendTextMessage(chatId.toChatId(), text, replyMarkup = keyboard)
    }

    override suspend fun setPersistentMenu(
        userId: UserId,
        items: List<MenuItem>,
    ) {
        val keyboard =
            ReplyKeyboardMarkup(
                keyboard = listOf(items.map { SimpleKeyboardButton(it.text) }),
                resizeKeyboard = true,
                persistent = true,
            )
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
        // No-op stub — replaced in 19.4 TelegramBotAdapter rewrite
    }

    suspend fun start(): Job {
        val (exec, job) =
            telegramBotWithBehaviourAndLongPolling(token, coroutineScope) {
                onText { message ->
                    val telegramId =
                        (message.chat.id as? IdChatIdentifier)?.chatId?.long ?: return@onText
                    val user = userRepository.findByTelegramId(telegramId)
                    user?.let { chatIdCache[it.id] = telegramId }
                    val incoming =
                        IncomingMessage(
                            telegramId = telegramId,
                            text = message.content.text,
                            userId = user?.id,
                        )
                    messageHandlers.forEach { it(incoming) }
                }
            }
        executor = exec
        return job
    }

    private suspend fun resolveChatId(userId: UserId): Long? =
        chatIdCache[userId] ?: userRepository.findById(userId)?.telegramId?.also { telegramId ->
            chatIdCache[userId] = telegramId
        }
}
