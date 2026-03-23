package mona.application.settings

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.PaymentDelayDays
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.UserRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val USER_ID = UserId("u1")

private fun makeUser(
    confirmBeforeCreate: Boolean = true,
    defaultPaymentDelayDays: PaymentDelayDays = PaymentDelayDays(30),
): User =
    User(
        id = USER_ID,
        telegramId = 100L,
        email = null,
        name = "Sophie",
        siren = null,
        siret = null,
        address = null,
        ibanEncrypted = null,
        activityType = ActivityType.BNC,
        declarationPeriodicity = DeclarationPeriodicity.MONTHLY,
        confirmBeforeCreate = confirmBeforeCreate,
        defaultPaymentDelayDays = defaultPaymentDelayDays,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private class StubUserRepository(user: User? = makeUser()) : UserRepository {
    val store = mutableMapOf<UserId, User>().apply { user?.let { put(it.id, it) } }

    override suspend fun findById(id: UserId): User? = store[id]

    override suspend fun findByTelegramId(telegramId: Long): User? = store.values.find { it.telegramId == telegramId }

    override suspend fun save(user: User) {
        store[user.id] = user
    }

    override suspend fun findAllWithPeriodicity(): List<User> = store.values.filter { it.declarationPeriodicity != null }

    override suspend fun findAllWithoutSiren(): List<User> = store.values.filter { it.siren == null }
}

private fun makeUseCase(repo: UserRepository = StubUserRepository()): ConfigureSetting = ConfigureSetting(repo)

private fun makeCommand(
    setting: String,
    value: String,
    userId: UserId = USER_ID,
): ConfigureSettingCommand = ConfigureSettingCommand(userId = userId, setting = setting, value = value)

class ConfigureSettingTest {
    @Test
    fun `sets confirm_before_create to false`() =
        runBlocking {
            val repo = StubUserRepository(makeUser(confirmBeforeCreate = true))
            val result = makeUseCase(repo).execute(makeCommand("confirm_before_create", "false"))

            assertIs<DomainResult.Ok<User>>(result)
            assertEquals(false, result.value.confirmBeforeCreate)
            assertEquals(false, repo.store[USER_ID]!!.confirmBeforeCreate)
        }

    @Test
    fun `sets confirm_before_create to true via oui`() =
        runBlocking {
            val repo = StubUserRepository(makeUser(confirmBeforeCreate = false))
            val result = makeUseCase(repo).execute(makeCommand("confirm_before_create", "oui"))

            assertIs<DomainResult.Ok<User>>(result)
            assertTrue(result.value.confirmBeforeCreate)
        }

    @Test
    fun `updates default_payment_delay_days`() =
        runBlocking {
            val repo = StubUserRepository(makeUser())
            val result = makeUseCase(repo).execute(makeCommand("default_payment_delay_days", "15"))

            assertIs<DomainResult.Ok<User>>(result)
            assertEquals(15, result.value.defaultPaymentDelayDays.value)
            assertEquals(15, repo.store[USER_ID]!!.defaultPaymentDelayDays.value)
        }

    @Test
    fun `clamps default_payment_delay_days to maximum of 60`() =
        runBlocking {
            val repo = StubUserRepository(makeUser())
            val result = makeUseCase(repo).execute(makeCommand("default_payment_delay_days", "90"))

            assertIs<DomainResult.Ok<User>>(result)
            assertEquals(60, result.value.defaultPaymentDelayDays.value)
        }

    @Test
    fun `returns InvalidSettingValue for non-integer payment delay`() =
        runBlocking {
            val result = makeUseCase().execute(makeCommand("default_payment_delay_days", "abc"))

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvalidSettingValue>(result.error)
        }

    @Test
    fun `returns UnknownSetting for unrecognised setting name`() =
        runBlocking {
            val result = makeUseCase().execute(makeCommand("unknown_param", "true"))

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.UnknownSetting>(result.error)
        }

    @Test
    fun `returns ProfileIncomplete when user not found`() =
        runBlocking {
            val repo = StubUserRepository(user = null)
            val result = makeUseCase(repo).execute(makeCommand("confirm_before_create", "false"))

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ProfileIncomplete>(result.error)
        }

    @Test
    fun `persists updated user to repository`() =
        runBlocking {
            val repo = StubUserRepository(makeUser())
            makeUseCase(repo).execute(makeCommand("default_payment_delay_days", "45"))

            assertEquals(45, repo.store[USER_ID]!!.defaultPaymentDelayDays.value)
        }
}
