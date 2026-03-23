package mona.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mona.domain.model.ActivityType
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.Email
import mona.domain.model.PaymentDelayDays
import mona.domain.model.Siren
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.ConversationMessage
import mona.domain.port.MessageRole
import mona.infrastructure.llm.PromptBuilder
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptBuilderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun makeUser(
        name: String? = null,
        siren: Siren? = null,
        email: Email? = null,
    ) = User(
        id = UserId(UUID.randomUUID().toString()),
        telegramId = 12345L,
        email = email,
        name = name,
        siren = siren,
        siret = null,
        address = null,
        ibanEncrypted = null,
        activityType = ActivityType.BIC_SERVICE,
        declarationPeriodicity = DeclarationPeriodicity.QUARTERLY,
        confirmBeforeCreate = true,
        defaultPaymentDelayDays = PaymentDelayDays(30),
        createdAt = Instant.now(),
    )

    private fun makeMessage(
        content: String,
        role: MessageRole = MessageRole.USER,
    ) = ConversationMessage(
        id = UUID.randomUUID().toString(),
        userId = UserId("user-1"),
        role = role,
        content = content,
        createdAt = Instant.now(),
    )

    // ── System prompt tests ──────────────────────────────────────────────────

    @Test
    fun `system prompt is in French`() {
        assertTrue(PromptBuilder.SYSTEM_PROMPT.contains("français"))
    }

    @Test
    fun `system prompt uses tu form`() {
        assertTrue(
            PromptBuilder.SYSTEM_PROMPT.contains("tutoyant") ||
                PromptBuilder.SYSTEM_PROMPT.contains("tutoyer") ||
                PromptBuilder.SYSTEM_PROMPT.contains("tu"),
        )
    }

    @Test
    fun `system prompt instructs to use unknown tool when unparseable`() {
        assertTrue(PromptBuilder.SYSTEM_PROMPT.contains("`unknown`"))
    }

    @Test
    fun `system prompt stays under 800 tokens approximately`() {
        // Rough approximation: 1 token ≈ 4 characters
        val estimatedTokens = PromptBuilder.SYSTEM_PROMPT.length / 4
        assertTrue(estimatedTokens < 800, "System prompt estimated at $estimatedTokens tokens (limit 800)")
    }

    // ── User context JSON tests ──────────────────────────────────────────────

    @Test
    fun `user context includes name when set`() {
        val user = makeUser(name = "Sophie", siren = Siren("123456789"))
        val contextJson = json.parseToJsonElement(PromptBuilder.buildUserContext(user)).jsonObject
        val userCtx = contextJson["user_context"]!!.jsonObject
        assertEquals("Sophie", userCtx["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `user context omits name when null`() {
        val user = makeUser(name = null)
        val contextJson = json.parseToJsonElement(PromptBuilder.buildUserContext(user)).jsonObject
        val userCtx = contextJson["user_context"]!!.jsonObject
        assertNull(userCtx["name"])
    }

    @Test
    fun `user context has_siren is true when siren set`() {
        val user = makeUser(siren = Siren("123456789"))
        val contextJson = json.parseToJsonElement(PromptBuilder.buildUserContext(user)).jsonObject
        val userCtx = contextJson["user_context"]!!.jsonObject
        assertTrue(userCtx["has_siren"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `user context has_siren is false when no siren`() {
        val user = makeUser(siren = null)
        val contextJson = json.parseToJsonElement(PromptBuilder.buildUserContext(user)).jsonObject
        val userCtx = contextJson["user_context"]!!.jsonObject
        assertFalse(userCtx["has_siren"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `onboarding step is complete when siren is set`() {
        val user = makeUser(name = "Sophie", siren = Siren("123456789"))
        val contextJson = json.parseToJsonElement(PromptBuilder.buildUserContext(user)).jsonObject
        val userCtx = contextJson["user_context"]!!.jsonObject
        assertEquals("complete", userCtx["onboarding_step"]?.jsonPrimitive?.content)
    }

    @Test
    fun `onboarding step is awaiting_siren when name set but no siren`() {
        val user = makeUser(name = "Sophie", siren = null)
        val contextJson = json.parseToJsonElement(PromptBuilder.buildUserContext(user)).jsonObject
        val userCtx = contextJson["user_context"]!!.jsonObject
        assertEquals("awaiting_siren", userCtx["onboarding_step"]?.jsonPrimitive?.content)
    }

    @Test
    fun `onboarding step is new_user when no name and no siren`() {
        val user = makeUser(name = null, siren = null)
        val contextJson = json.parseToJsonElement(PromptBuilder.buildUserContext(user)).jsonObject
        val userCtx = contextJson["user_context"]!!.jsonObject
        assertEquals("new_user", userCtx["onboarding_step"]?.jsonPrimitive?.content)
    }

    // ── buildContext tests ───────────────────────────────────────────────────

    @Test
    fun `buildContext uses system prompt`() {
        val user = makeUser(name = "Sophie")
        val ctx = PromptBuilder.buildContext(user, emptyList())
        assertEquals(PromptBuilder.SYSTEM_PROMPT, ctx.systemPrompt)
    }

    @Test
    fun `buildContext includes user context json`() {
        val user = makeUser(name = "Sophie", siren = Siren("123456789"))
        val ctx = PromptBuilder.buildContext(user, emptyList())
        assertTrue(ctx.userContextJson.isNotBlank())
        val parsed = json.parseToJsonElement(ctx.userContextJson)
        assertNotNull(parsed.jsonObject["user_context"])
    }

    @Test
    fun `buildContext takes at most 3 messages`() {
        val user = makeUser()
        val messages = (1..5).map { makeMessage("message $it") }
        val ctx = PromptBuilder.buildContext(user, messages)
        assertEquals(3, ctx.messages.size)
    }

    @Test
    fun `buildContext keeps last 3 messages in order`() {
        val user = makeUser()
        val messages = (1..5).map { makeMessage("message $it") }
        val ctx = PromptBuilder.buildContext(user, messages)
        assertEquals("message 3", ctx.messages[0].content)
        assertEquals("message 4", ctx.messages[1].content)
        assertEquals("message 5", ctx.messages[2].content)
    }

    @Test
    fun `buildContext handles fewer than 3 messages`() {
        val user = makeUser()
        val messages = listOf(makeMessage("only one"))
        val ctx = PromptBuilder.buildContext(user, messages)
        assertEquals(1, ctx.messages.size)
        assertEquals("only one", ctx.messages[0].content)
    }

    @Test
    fun `total system prompt and user context stay under 800 tokens approximately`() {
        val user = makeUser(name = "Marie-Christine Dupont-Lefevre", siren = Siren("123456789"))
        val ctx = PromptBuilder.buildContext(user, emptyList())
        val totalChars = ctx.systemPrompt.length + ctx.userContextJson.length
        val estimatedTokens = totalChars / 4
        assertTrue(estimatedTokens < 800, "Total estimated at $estimatedTokens tokens (limit 800)")
    }
}
