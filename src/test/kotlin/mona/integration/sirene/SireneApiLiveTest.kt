package mona.integration.sirene

import kotlinx.coroutines.runBlocking
import mona.domain.model.DomainResult
import mona.domain.model.Siren
import mona.infrastructure.sirene.SireneApiClient
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Live integration tests against the real INSEE SIRENE API.
 * Gated behind LIVE_API_TESTS=true to avoid network calls in CI.
 */
@EnabledIfEnvironmentVariable(named = "LIVE_API_TESTS", matches = "true")
class SireneApiLiveTest {
    private val client = SireneApiClient.fromEnv()

    @Test
    fun `live lookup returns valid result for known SIREN`() =
        runBlocking {
            // Use a well-known, stable SIREN (e.g., an INSEE SIREN that won't disappear)
            val result = client.lookupBySiren(Siren("552032534")) // SOCIETE GENERALE
            assertIs<DomainResult.Ok<*>>(result)
            val sireneResult = (result as DomainResult.Ok).value
            assertTrue(sireneResult.legalName.isNotBlank())
        }

    @Test
    fun `live search returns results for common name and city`() =
        runBlocking {
            val result = client.searchByNameAndCity("Dupont", "Paris")
            assertIs<DomainResult.Ok<*>>(result)
            // Just verify no exception thrown and result parsed
        }
}
