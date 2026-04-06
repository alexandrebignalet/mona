package mona.integration.onboarding

import kotlinx.coroutines.runBlocking
import mona.application.onboarding.SetupProfile
import mona.application.onboarding.SetupProfileCommand
import mona.application.onboarding.SetupProfileResult
import mona.domain.model.DomainResult
import mona.domain.model.Siren
import mona.integration.IntegrationTestBase
import mona.integration.SireneScenario
import mona.integration.TEST_SIREN
import mona.integration.TEST_SIRET
import mona.integration.TEST_USER_MINIMAL_ID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LookupSirenTest : IntegrationTestBase() {
    private fun useCase() = SetupProfile(userRepo, sirenePort, cryptoPort)

    @BeforeTest
    fun seedUser() =
        setup {
            createTestUserMinimal()
        }

    @Test
    fun `valid SIREN populates user profile from Sirene`() =
        runBlocking {
            fakeSireneExecutor.scenario = SireneScenario.LookupSuccess
            val result =
                useCase().execute(
                    SetupProfileCommand.LookupSiren(userId = TEST_USER_MINIMAL_ID, siren = TEST_SIREN),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val found = assertIs<SetupProfileResult.SirenFound>(result.value)
            assertEquals(TEST_SIREN, found.user.siren)
            assertEquals(TEST_SIRET, found.user.siret)
            assertNotNull(found.user.name)
            // persisted in DB
            val saved = userRepo.findById(TEST_USER_MINIMAL_ID)
            assertNotNull(saved)
            assertEquals(TEST_SIREN, saved.siren)
        }

    @Test
    fun `SIREN not found returns Err`() =
        runBlocking {
            fakeSireneExecutor.scenario = SireneScenario.LookupNotFound
            val result =
                useCase().execute(
                    SetupProfileCommand.LookupSiren(userId = TEST_USER_MINIMAL_ID, siren = Siren("000000000")),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `ceased company returns Err`() =
        runBlocking {
            fakeSireneExecutor.scenario = SireneScenario.LookupCeased
            val result =
                useCase().execute(
                    SetupProfileCommand.LookupSiren(userId = TEST_USER_MINIMAL_ID, siren = Siren("999999999")),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `user who already has SIREN gets it updated`() =
        runBlocking {
            fakeSireneExecutor.scenario = SireneScenario.LookupSuccess
            // create fully-onboarded user (already has SIREN)
            createTestUser()
            val result =
                useCase().execute(
                    SetupProfileCommand.LookupSiren(userId = mona.integration.TEST_USER_ID, siren = TEST_SIREN),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val found = assertIs<SetupProfileResult.SirenFound>(result.value)
            // SIREN and SIRET are updated from Sirene
            assertNotNull(found.user.siren)
            assertNotNull(found.user.siret)
        }
}
