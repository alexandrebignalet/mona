package mona.application.onboarding

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PostalAddress
import mona.domain.model.Siren
import mona.domain.model.Siret
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.CryptoPort
import mona.domain.port.SirenePort
import mona.domain.port.SireneResult
import mona.domain.port.UserRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val USER_ID = UserId("u1")
private val SIREN = Siren("123456789")
private val SIRET = Siret("12345678900001")

private fun makeUser(
    siren: Siren? = null,
    name: String? = null,
): User =
    User(
        id = USER_ID,
        telegramId = 1L,
        email = null,
        name = name,
        siren = siren,
        siret = null,
        address = null,
        ibanEncrypted = null,
        activityType = null,
        declarationPeriodicity = null,
        confirmBeforeCreate = true,
        defaultPaymentDelayDays = PaymentDelayDays(30),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private class InMemoryUserRepo(user: User) : UserRepository {
    private val store = mutableMapOf(user.id to user)

    override suspend fun findById(id: UserId): User? = store[id]

    override suspend fun findByTelegramId(telegramId: Long): User? = store.values.find { it.telegramId == telegramId }

    override suspend fun save(user: User) {
        store[user.id] = user
    }

    override suspend fun findAllWithPeriodicity(): List<User> = store.values.filter { it.declarationPeriodicity != null }

    fun get(): User? = store[USER_ID]
}

private class StubSirenePort(
    private val lookupResult: DomainResult<SireneResult>,
    private val searchResult: DomainResult<List<SireneResult>> = DomainResult.Ok(emptyList()),
) : SirenePort {
    override suspend fun lookupBySiren(siren: Siren): DomainResult<SireneResult> = lookupResult

    override suspend fun searchByNameAndCity(
        name: String,
        city: String,
    ): DomainResult<List<SireneResult>> = searchResult
}

private class RecordingCryptoPort : CryptoPort {
    var lastEncrypted: String? = null

    override fun encrypt(plaintext: String): ByteArray {
        lastEncrypted = plaintext
        return plaintext.toByteArray()
    }

    override fun decrypt(ciphertext: ByteArray): String = String(ciphertext)
}

private fun makeSireneResult(name: String = "Sophie Martin") =
    SireneResult(
        legalName = name,
        siren = SIREN,
        siret = SIRET,
        address = PostalAddress("12 rue de la Paix", "75002", "Paris"),
        activityType = ActivityType.BNC,
    )

private fun makeUseCase(
    user: User = makeUser(),
    lookupResult: DomainResult<SireneResult> = DomainResult.Ok(makeSireneResult()),
    searchResult: DomainResult<List<SireneResult>> = DomainResult.Ok(listOf(makeSireneResult())),
    crypto: CryptoPort = RecordingCryptoPort(),
): Triple<SetupProfile, InMemoryUserRepo, RecordingCryptoPort> {
    val repo = InMemoryUserRepo(user)
    val cryptoPort = crypto as? RecordingCryptoPort ?: RecordingCryptoPort()
    val uc = SetupProfile(repo, StubSirenePort(lookupResult, searchResult), cryptoPort)
    return Triple(uc, repo, cryptoPort)
}

class SetupProfileTest {
    @Test
    fun `LookupSiren auto-fills user name, address, activityType and returns SirenFound`() =
        runBlocking {
            val (uc, repo, _) = makeUseCase()
            val result = uc.execute(SetupProfileCommand.LookupSiren(USER_ID, SIREN))
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            assertIs<SetupProfileResult.SirenFound>(result.value)
            val updated = repo.get()!!
            assertEquals(SIREN, updated.siren)
            assertEquals(SIRET, updated.siret)
            assertEquals("Sophie Martin", updated.name)
            assertEquals(PostalAddress("12 rue de la Paix", "75002", "Paris"), updated.address)
            assertEquals(ActivityType.BNC, updated.activityType)
        }

    @Test
    fun `LookupSiren propagates SirenNotFound error`() =
        runBlocking {
            val (uc, _, _) = makeUseCase(lookupResult = DomainResult.Err(DomainError.SirenNotFound(SIREN)))
            val result = uc.execute(SetupProfileCommand.LookupSiren(USER_ID, SIREN))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SirenNotFound>(result.error)
        }

    @Test
    fun `SearchSiren returns SirenMatches without loading user`() =
        runBlocking {
            val sirene1 = makeSireneResult("Sophie Martin")
            val sirene2 = makeSireneResult("Sophie Dupont-Martin")
            val (uc, _, _) =
                makeUseCase(
                    searchResult = DomainResult.Ok(listOf(sirene1, sirene2)),
                )
            val result = uc.execute(SetupProfileCommand.SearchSiren(USER_ID, "Sophie Martin", "Paris"))
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val matches = (result.value as SetupProfileResult.SirenMatches).matches
            assertEquals(2, matches.size)
        }

    @Test
    fun `SearchSiren propagates lookup failure`() =
        runBlocking {
            val (uc, _, _) =
                makeUseCase(
                    searchResult = DomainResult.Err(DomainError.SireneLookupFailed("timeout")),
                )
            val result = uc.execute(SetupProfileCommand.SearchSiren(USER_ID, "Sophie", "Lyon"))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SireneLookupFailed>(result.error)
        }

    @Test
    fun `ApplySireneResult applies chosen search result to user`() =
        runBlocking {
            val (uc, repo, _) = makeUseCase()
            val sirene = makeSireneResult("Sophie Dupont-Martin")
            val result = uc.execute(SetupProfileCommand.ApplySireneResult(USER_ID, sirene))
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            assertIs<SetupProfileResult.Updated>(result.value)
            val updated = repo.get()!!
            assertEquals(SIREN, updated.siren)
            assertEquals("Sophie Dupont-Martin", updated.name)
        }

    @Test
    fun `UpdateFields updates name and preserves existing fields`() =
        runBlocking {
            val user = makeUser(siren = SIREN, name = "Old Name")
            val repo = InMemoryUserRepo(user)
            val uc = SetupProfile(repo, StubSirenePort(DomainResult.Ok(makeSireneResult())), RecordingCryptoPort())
            val result =
                uc.execute(
                    SetupProfileCommand.UpdateFields(
                        userId = USER_ID,
                        name = "New Name",
                    ),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val updated = repo.get()!!
            assertEquals("New Name", updated.name)
            assertEquals(SIREN, updated.siren)
        }

    @Test
    fun `UpdateFields sets payment delay, email, and periodicity`() =
        runBlocking {
            val (uc, repo, _) = makeUseCase()
            val result =
                uc.execute(
                    SetupProfileCommand.UpdateFields(
                        userId = USER_ID,
                        defaultPaymentDelayDays = PaymentDelayDays(15),
                        email = Email("sophie@example.com"),
                        declarationPeriodicity = DeclarationPeriodicity.QUARTERLY,
                    ),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val updated = repo.get()!!
            assertEquals(PaymentDelayDays(15), updated.defaultPaymentDelayDays)
            assertEquals(Email("sophie@example.com"), updated.email)
            assertEquals(DeclarationPeriodicity.QUARTERLY, updated.declarationPeriodicity)
        }

    @Test
    fun `SetIban encrypts IBAN and persists`() =
        runBlocking {
            val repo = InMemoryUserRepo(makeUser())
            val crypto = RecordingCryptoPort()
            val uc = SetupProfile(repo, StubSirenePort(DomainResult.Ok(makeSireneResult())), crypto)
            val result = uc.execute(SetupProfileCommand.SetIban(USER_ID, "FR7630001007941234567890185"))
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            assertEquals("FR7630001007941234567890185", crypto.lastEncrypted)
            val updated = repo.get()!!
            assertNotNull(updated.ibanEncrypted)
            assertContentEquals("FR7630001007941234567890185".toByteArray(), updated.ibanEncrypted)
        }

    @Test
    fun `returns ProfileIncomplete when user not found`() =
        runBlocking {
            val repo = InMemoryUserRepo(makeUser())
            val uc = SetupProfile(repo, StubSirenePort(DomainResult.Ok(makeSireneResult())), RecordingCryptoPort())
            val result = uc.execute(SetupProfileCommand.LookupSiren(UserId("unknown"), SIREN))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ProfileIncomplete>(result.error)
        }

    @Test
    fun `LookupSiren preserves existing address when SIRENE returns null address`() =
        runBlocking {
            val existingAddress = PostalAddress("8 av Montaigne", "75008", "Paris")
            val user = makeUser().copy(address = existingAddress)
            val repo = InMemoryUserRepo(user)
            val noAddressResult =
                SireneResult(
                    legalName = "Sophie Martin",
                    siren = SIREN,
                    siret = SIRET,
                    address = null,
                    activityType = null,
                )
            val uc = SetupProfile(repo, StubSirenePort(DomainResult.Ok(noAddressResult)), RecordingCryptoPort())
            uc.execute(SetupProfileCommand.LookupSiren(USER_ID, SIREN))
            val updated = repo.get()!!
            assertEquals(existingAddress, updated.address)
        }

    @Test
    fun `SetIban user not found returns ProfileIncomplete`() =
        runBlocking {
            val repo = InMemoryUserRepo(makeUser())
            val uc = SetupProfile(repo, StubSirenePort(DomainResult.Ok(makeSireneResult())), RecordingCryptoPort())
            val result = uc.execute(SetupProfileCommand.SetIban(UserId("missing"), "FR000"))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ProfileIncomplete>(result.error)
        }

    @Test
    fun `SirenFound result contains updated user`() =
        runBlocking {
            val (uc, _, _) = makeUseCase()
            val result = uc.execute(SetupProfileCommand.LookupSiren(USER_ID, SIREN))
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val sirenFound = result.value as SetupProfileResult.SirenFound
            assertEquals(SIREN, sirenFound.user.siren)
            assertNull(sirenFound.user.ibanEncrypted)
        }
}
