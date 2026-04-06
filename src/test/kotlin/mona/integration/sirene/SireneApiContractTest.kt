package mona.integration.sirene

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.DomainResult
import mona.domain.model.Siren
import mona.infrastructure.sirene.SireneApiClient
import mona.integration.FakeSireneHttpExecutor
import mona.integration.SireneScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SireneApiContractTest {
    private val fakeExecutor = FakeSireneHttpExecutor()
    private val client = SireneApiClient(httpExecutor = fakeExecutor)

    @Test
    fun `lookupBySiren success returns SireneResult with correct fields`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.LookupSuccess
            val result = client.lookupBySiren(Siren("123456789"))

            assertIs<DomainResult.Ok<*>>(result)
            val sireneResult = (result as DomainResult.Ok).value
            assertEquals(Siren("123456789"), sireneResult.siren)
            assertTrue(sireneResult.legalName.isNotBlank())
            assertNotNull(sireneResult.siret)
        }

    @Test
    fun `lookupBySiren not found returns SirenNotFound error`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.LookupNotFound
            val result = client.lookupBySiren(Siren("000000000"))
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `lookupBySiren ceased returns error`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.LookupCeased
            val result = client.lookupBySiren(Siren("000000000"))
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `lookupBySiren address is assembled correctly`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.LookupSuccess
            val result = client.lookupBySiren(Siren("123456789"))

            assertIs<DomainResult.Ok<*>>(result)
            val sireneResult = (result as DomainResult.Ok).value
            assertNotNull(sireneResult.address)
            assertTrue(sireneResult.address!!.postalCode.isNotBlank())
            assertTrue(sireneResult.address!!.city.isNotBlank())
        }

    @Test
    fun `lookupBySiren NAF code maps to ActivityType`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.LookupSuccess
            val result = client.lookupBySiren(Siren("123456789"))

            assertIs<DomainResult.Ok<*>>(result)
            val sireneResult = (result as DomainResult.Ok).value
            // NAF 62.01Z → BIC_SERVICE
            assertEquals(ActivityType.BIC_SERVICE, sireneResult.activityType)
        }

    @Test
    fun `searchByNameAndCity single match returns list of one`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.SearchSingleMatch
            val result = client.searchByNameAndCity("Dupont", "Paris")

            assertIs<DomainResult.Ok<*>>(result)
            val list = (result as DomainResult.Ok).value as List<*>
            assertEquals(1, list.size)
        }

    @Test
    fun `searchByNameAndCity multiple matches returns multiple results`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.SearchMultipleMatches
            val result = client.searchByNameAndCity("Martin", "Lyon")

            assertIs<DomainResult.Ok<*>>(result)
            val list = (result as DomainResult.Ok).value as List<*>
            assertTrue(list.size > 1)
        }

    @Test
    fun `searchByNameAndCity no match returns empty list`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.SearchNoMatch
            val result = client.searchByNameAndCity("Zzz", "Nowhere")

            assertIs<DomainResult.Ok<*>>(result)
            val list = (result as DomainResult.Ok).value as List<*>
            assertTrue(list.isEmpty())
        }

    @Test
    fun `searchByNameAndCity malformed JSON returns Err`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.SearchMalformed
            val result = client.searchByNameAndCity("Test", "Test")

            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `lookupBySiren success SIRET is populated`() =
        runBlocking {
            fakeExecutor.scenario = SireneScenario.LookupSuccess
            val result = client.lookupBySiren(Siren("123456789"))

            assertIs<DomainResult.Ok<*>>(result)
            val sireneResult = (result as DomainResult.Ok).value
            // From fixture: siret = "12345678900012"
            assertEquals("12345678900012", sireneResult.siret.value)
        }
}
