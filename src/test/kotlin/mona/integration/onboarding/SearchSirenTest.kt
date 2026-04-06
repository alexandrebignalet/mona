package mona.integration.onboarding

import kotlinx.coroutines.runBlocking
import mona.application.onboarding.SetupProfile
import mona.application.onboarding.SetupProfileCommand
import mona.application.onboarding.SetupProfileResult
import mona.domain.model.DomainResult
import mona.domain.model.Siren
import mona.domain.model.UserId
import mona.integration.IntegrationTestBase
import mona.integration.SireneScenario
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val SEARCH_USER_ID = UserId("search-user")

class SearchSirenTest : IntegrationTestBase() {
    private fun useCase() = SetupProfile(userRepo, sirenePort, cryptoPort)

    @Test
    fun `single match returns SirenMatches with one result`() =
        runBlocking {
            fakeSireneExecutor.scenario = SireneScenario.SearchSingleMatch
            val result =
                useCase().execute(
                    SetupProfileCommand.SearchSiren(userId = SEARCH_USER_ID, name = "Dupont", city = "Paris"),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val matches = assertIs<SetupProfileResult.SirenMatches>(result.value)
            assertTrue(matches.matches.size == 1)
        }

    @Test
    fun `single match result contains SIREN and legal name from fixture`() =
        runBlocking {
            fakeSireneExecutor.scenario = SireneScenario.SearchSingleMatch
            val result =
                useCase().execute(
                    SetupProfileCommand.SearchSiren(userId = SEARCH_USER_ID, name = "Dupont", city = "Paris"),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val matches = assertIs<SetupProfileResult.SirenMatches>(result.value)
            val match = matches.matches.first()
            assertTrue(match.siren == Siren(match.siren.value))
            assertTrue(match.legalName.isNotBlank())
        }

    @Test
    fun `multiple matches returns SirenMatches with many results`() =
        runBlocking {
            fakeSireneExecutor.scenario = SireneScenario.SearchMultipleMatches
            val result =
                useCase().execute(
                    SetupProfileCommand.SearchSiren(userId = SEARCH_USER_ID, name = "Martin", city = "Lyon"),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val matches = assertIs<SetupProfileResult.SirenMatches>(result.value)
            assertTrue(matches.matches.size > 1)
        }

    @Test
    fun `no match returns SirenMatches with empty list`() =
        runBlocking {
            fakeSireneExecutor.scenario = SireneScenario.SearchNoMatch
            val result =
                useCase().execute(
                    SetupProfileCommand.SearchSiren(userId = SEARCH_USER_ID, name = "Zzzznotfound", city = "Nowhere"),
                )
            assertIs<DomainResult.Ok<SetupProfileResult>>(result)
            val matches = assertIs<SetupProfileResult.SirenMatches>(result.value)
            assertTrue(matches.matches.isEmpty())
        }

    @Test
    fun `malformed API response returns Err`() =
        runBlocking {
            fakeSireneExecutor.scenario = SireneScenario.SearchMalformed
            val result =
                useCase().execute(
                    SetupProfileCommand.SearchSiren(userId = SEARCH_USER_ID, name = "Test", city = "Test"),
                )
            assertIs<DomainResult.Err>(result)
        }
}
