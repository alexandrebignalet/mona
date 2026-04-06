package mona.integration.onboarding

import kotlinx.coroutines.runBlocking
import mona.application.onboarding.SetupProfile
import mona.application.onboarding.SetupProfileCommand
import mona.application.onboarding.SetupProfileResult
import mona.domain.model.ActivityType
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PostalAddress
import mona.integration.IntegrationTestBase
import mona.integration.TEST_IBAN_PLAIN
import mona.integration.TEST_USER_ID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SetupProfileTest : IntegrationTestBase() {
    private fun useCase() = SetupProfile(userRepo, sirenePort, cryptoPort)

    @BeforeTest
    fun seedUser() =
        setup {
            createTestUser()
        }

    @Test
    fun `set email updates user email in DB`() =
        runBlocking {
            val newEmail = Email("newemail@example.com")
            val result =
                useCase().execute(
                    SetupProfileCommand.UpdateFields(userId = TEST_USER_ID, email = newEmail),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val saved = userRepo.findById(TEST_USER_ID)
            assertEquals(newEmail, saved?.email)
        }

    @Test
    fun `set address updates user address in DB`() =
        runBlocking {
            val newAddress = PostalAddress("42 RUE DES LILAS", "92100", "BOULOGNE")
            val result =
                useCase().execute(
                    SetupProfileCommand.UpdateFields(userId = TEST_USER_ID, address = newAddress),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val saved = userRepo.findById(TEST_USER_ID)
            assertEquals(newAddress, saved?.address)
        }

    @Test
    fun `set IBAN encrypts and stores ciphertext`() =
        runBlocking {
            val result =
                useCase().execute(
                    SetupProfileCommand.SetIban(userId = TEST_USER_ID, plainIban = TEST_IBAN_PLAIN),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val saved = userRepo.findById(TEST_USER_ID)
            assertNotNull(saved?.ibanEncrypted)
            // Roundtrip decryption should yield original
            val decrypted = cryptoPort.decrypt(saved!!.ibanEncrypted!!)
            assertEquals(TEST_IBAN_PLAIN, decrypted)
        }

    @Test
    fun `set activity type updates user in DB`() =
        runBlocking {
            val result =
                useCase().execute(
                    SetupProfileCommand.UpdateFields(userId = TEST_USER_ID, activityType = ActivityType.BNC),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val saved = userRepo.findById(TEST_USER_ID)
            assertEquals(ActivityType.BNC, saved?.activityType)
        }

    @Test
    fun `set declaration periodicity updates user in DB`() =
        runBlocking {
            val result =
                useCase().execute(
                    SetupProfileCommand.UpdateFields(userId = TEST_USER_ID, declarationPeriodicity = DeclarationPeriodicity.QUARTERLY),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val saved = userRepo.findById(TEST_USER_ID)
            assertEquals(DeclarationPeriodicity.QUARTERLY, saved?.declarationPeriodicity)
        }

    @Test
    fun `set payment delay updates user in DB`() =
        runBlocking {
            val newDelay = PaymentDelayDays(45)
            val result =
                useCase().execute(
                    SetupProfileCommand.UpdateFields(userId = TEST_USER_ID, defaultPaymentDelayDays = newDelay),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val saved = userRepo.findById(TEST_USER_ID)
            assertEquals(newDelay, saved?.defaultPaymentDelayDays)
        }

    @Test
    fun `set name updates user in DB`() =
        runBlocking {
            val result =
                useCase().execute(
                    SetupProfileCommand.UpdateFields(userId = TEST_USER_ID, name = "Marie Curie"),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val saved = userRepo.findById(TEST_USER_ID)
            assertEquals("Marie Curie", saved?.name)
        }

    @Test
    fun `multiple fields updated in single call`() =
        runBlocking {
            val newEmail = Email("multi@example.com")
            val newAddress = PostalAddress("1 BD VOLTAIRE", "75011", "PARIS")
            val result =
                useCase().execute(
                    SetupProfileCommand.UpdateFields(
                        userId = TEST_USER_ID,
                        name = "Paul Verlaine",
                        email = newEmail,
                        address = newAddress,
                    ),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val saved = userRepo.findById(TEST_USER_ID)
            assertEquals("Paul Verlaine", saved?.name)
            assertEquals(newEmail, saved?.email)
            assertEquals(newAddress, saved?.address)
        }
}
