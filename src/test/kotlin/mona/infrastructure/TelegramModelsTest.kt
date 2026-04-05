package mona.infrastructure

import kotlinx.serialization.encodeToString
import mona.infrastructure.telegram.TgCallbackQuery
import mona.infrastructure.telegram.TgChat
import mona.infrastructure.telegram.TgMessage
import mona.infrastructure.telegram.TgUpdate
import mona.infrastructure.telegram.TgUser
import mona.infrastructure.telegram.telegramJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TelegramModelsTest {
    @Test
    fun `round-trip message update`() {
        val update =
            TgUpdate(
                updateId = 123L,
                message =
                    TgMessage(
                        messageId = 456L,
                        chat = TgChat(id = 789L),
                        text = "hello",
                    ),
            )
        val json = telegramJson.encodeToString(update)
        val decoded = telegramJson.decodeFromString<TgUpdate>(json)
        assertEquals(update, decoded)
    }

    @Test
    fun `round-trip callback_query update`() {
        val update =
            TgUpdate(
                updateId = 1L,
                callbackQuery =
                    TgCallbackQuery(
                        id = "cq-42",
                        from = TgUser(id = 100L),
                        data = "action:pay",
                    ),
            )
        val json = telegramJson.encodeToString(update)
        val decoded = telegramJson.decodeFromString<TgUpdate>(json)
        assertEquals(update, decoded)
        assertNull(decoded.message)
        assertNotNull(decoded.callbackQuery)
        assertEquals("cq-42", decoded.callbackQuery!!.id)
    }

    @Test
    fun `unknown JSON fields are silently ignored`() {
        val raw =
            """
            {
              "update_id": 99,
              "unknown_field": "ignored",
              "message": {
                "message_id": 1,
                "chat": {"id": 7, "extra_chat_field": true},
                "text": "hi",
                "date": 1700000000
              }
            }
            """.trimIndent()
        val decoded = telegramJson.decodeFromString<TgUpdate>(raw)
        assertEquals(99L, decoded.updateId)
        assertEquals("hi", decoded.message?.text)
        assertEquals(7L, decoded.message?.chat?.id)
    }

    @Test
    fun `message with null text is allowed`() {
        val raw = """{"update_id": 1, "message": {"message_id": 2, "chat": {"id": 3}}}"""
        val decoded = telegramJson.decodeFromString<TgUpdate>(raw)
        assertNull(decoded.message?.text)
    }

    @Test
    fun `callback_query with null data is allowed`() {
        val raw = """{"update_id": 1, "callback_query": {"id": "x", "from": {"id": 5}}}"""
        val decoded = telegramJson.decodeFromString<TgUpdate>(raw)
        assertNull(decoded.callbackQuery?.data)
    }
}
