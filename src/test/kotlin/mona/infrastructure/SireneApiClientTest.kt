package mona.infrastructure

import kotlinx.coroutines.test.runTest
import mona.domain.model.ActivityType
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Siren
import mona.domain.port.SireneResult
import mona.infrastructure.sirene.SireneApiClient
import mona.infrastructure.sirene.SireneHttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SireneApiClientTest {
    private fun client(handler: suspend (String) -> SireneHttpResponse): SireneApiClient =
        SireneApiClient(
            httpExecutor = { url -> handler(url) },
        )

    @Test
    fun `lookupBySiren returns Ok with company data on 200`() =
        runTest {
            val siren = Siren("123456789")
            val adapter =
                client { _ ->
                    SireneHttpResponse(
                        200,
                        """
                        {
                          "uniteLegale": {
                            "siren": "123456789",
                            "denominationUniteLegale": "MA SOCIETE SAS",
                            "activitePrincipaleUniteLegale": "62.01Z",
                            "etablissementSiege": {
                              "siret": "12345678900012",
                              "adresseEtablissement": {
                                "numeroVoieEtablissement": "12",
                                "typeVoieEtablissement": "RUE",
                                "libelleVoieEtablissement": "DE LA PAIX",
                                "codePostalEtablissement": "75001",
                                "libelleCommuneEtablissement": "PARIS"
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    )
                }
            val result = adapter.lookupBySiren(siren)
            assertIs<DomainResult.Ok<SireneResult>>(result)
            val data = result.value
            assertEquals("MA SOCIETE SAS", data.legalName)
            assertEquals("123456789", data.siren.value)
            assertEquals("12345678900012", data.siret.value)
            assertEquals(ActivityType.BIC_SERVICE, data.activityType)
            assertNotNull(data.address)
            assertEquals("75001", data.address!!.postalCode)
            assertEquals("PARIS", data.address!!.city)
            assertTrue(data.address!!.street.contains("RUE"))
        }

    @Test
    fun `lookupBySiren returns Ok with EI name from nom and prenom`() =
        runTest {
            val siren = Siren("987654321")
            val adapter =
                client { _ ->
                    SireneHttpResponse(
                        200,
                        """
                        {
                          "uniteLegale": {
                            "siren": "987654321",
                            "nomUniteLegale": "DUPONT",
                            "prenomUsuelUniteLegale": "JEAN",
                            "activitePrincipaleUniteLegale": "86.21Z",
                            "etablissementSiege": {
                              "siret": "98765432100023",
                              "adresseEtablissement": {
                                "codePostalEtablissement": "69001",
                                "libelleCommuneEtablissement": "LYON"
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    )
                }
            val result = adapter.lookupBySiren(siren)
            assertIs<DomainResult.Ok<SireneResult>>(result)
            val data = result.value
            assertEquals("JEAN DUPONT", data.legalName)
            assertEquals(ActivityType.BNC, data.activityType)
            assertNotNull(data.address)
            assertEquals("69001", data.address!!.postalCode)
            assertEquals("LYON", data.address!!.city)
        }

    @Test
    fun `lookupBySiren returns Err SirenNotFound on 404`() =
        runTest {
            val siren = Siren("000000001")
            val adapter = client { _ -> SireneHttpResponse(404, """{"header":{"message":"404"}}""") }
            val result = adapter.lookupBySiren(siren)
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SirenNotFound>(result.error)
            assertEquals(siren, (result.error as DomainError.SirenNotFound).siren)
        }

    @Test
    fun `lookupBySiren returns Err SireneLookupFailed on 500`() =
        runTest {
            val adapter = client { _ -> SireneHttpResponse(500, "Internal Server Error") }
            val result = adapter.lookupBySiren(Siren("123456789"))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SireneLookupFailed>(result.error)
            assertTrue((result.error as DomainError.SireneLookupFailed).reason.contains("500"))
        }

    @Test
    fun `lookupBySiren returns Err SireneLookupFailed on 401`() =
        runTest {
            val adapter = client { _ -> SireneHttpResponse(401, """{"message":"Unauthorized"}""") }
            val result = adapter.lookupBySiren(Siren("123456789"))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SireneLookupFailed>(result.error)
        }

    @Test
    fun `lookupBySiren calls correct URL`() =
        runTest {
            var capturedUrl: String? = null
            val adapter =
                client { url ->
                    capturedUrl = url
                    SireneHttpResponse(404, "{}")
                }
            adapter.lookupBySiren(Siren("123456789"))
            assertTrue(capturedUrl!!.contains("/siren/123456789"))
        }

    @Test
    fun `searchByNameAndCity returns list on 200`() =
        runTest {
            val adapter =
                client { _ ->
                    SireneHttpResponse(
                        200,
                        """
                        {
                          "header": {"total": 1, "debut": 0, "nombre": 1},
                          "etablissements": [
                            {
                              "siret": "12345678900012",
                              "uniteLegale": {
                                "denominationUniteLegale": "MA SOCIETE SAS",
                                "activitePrincipaleUniteLegale": "47.11B"
                              },
                              "adresseEtablissement": {
                                "numeroVoieEtablissement": "5",
                                "typeVoieEtablissement": "AVENUE",
                                "libelleVoieEtablissement": "DU GENERAL DE GAULLE",
                                "codePostalEtablissement": "75008",
                                "libelleCommuneEtablissement": "PARIS"
                              }
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                }
            val result = adapter.searchByNameAndCity("MA SOCIETE", "PARIS")
            assertIs<DomainResult.Ok<List<SireneResult>>>(result)
            val list = result.value
            assertEquals(1, list.size)
            val item = list[0]
            assertEquals("MA SOCIETE SAS", item.legalName)
            assertEquals("12345678900012", item.siret.value)
            assertEquals("123456789", item.siren.value)
            assertEquals(ActivityType.BIC_VENTE, item.activityType)
        }

    @Test
    fun `searchByNameAndCity returns empty list on 404`() =
        runTest {
            val adapter = client { _ -> SireneHttpResponse(404, "{}") }
            val result = adapter.searchByNameAndCity("INEXISTANT", "PARIS")
            assertIs<DomainResult.Ok<List<SireneResult>>>(result)
            assertTrue(result.value.isEmpty())
        }

    @Test
    fun `searchByNameAndCity returns Err on 503`() =
        runTest {
            val adapter = client { _ -> SireneHttpResponse(503, "Service unavailable") }
            val result = adapter.searchByNameAndCity("COMPANY", "PARIS")
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SireneLookupFailed>(result.error)
        }

    @Test
    fun `lookupBySiren maps manufacturing NAF code to BIC_VENTE`() =
        runTest {
            val adapter =
                client { _ ->
                    SireneHttpResponse(
                        200,
                        """
                        {
                          "uniteLegale": {
                            "siren": "111111111",
                            "denominationUniteLegale": "BOULANGERIE DU COIN",
                            "activitePrincipaleUniteLegale": "10.71C",
                            "etablissementSiege": {
                              "siret": "11111111100011",
                              "adresseEtablissement": {
                                "codePostalEtablissement": "75015",
                                "libelleCommuneEtablissement": "PARIS"
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    )
                }
            val result = adapter.lookupBySiren(Siren("111111111"))
            assertIs<DomainResult.Ok<SireneResult>>(result)
            assertEquals(ActivityType.BIC_VENTE, result.value.activityType)
        }

    @Test
    fun `lookupBySiren maps legal services NAF code to BNC`() =
        runTest {
            val adapter =
                client { _ ->
                    SireneHttpResponse(
                        200,
                        """
                        {
                          "uniteLegale": {
                            "siren": "222222222",
                            "denominationUniteLegale": "CABINET LEGALIS",
                            "activitePrincipaleUniteLegale": "69.10Z",
                            "etablissementSiege": {
                              "siret": "22222222200022",
                              "adresseEtablissement": {
                                "codePostalEtablissement": "75001",
                                "libelleCommuneEtablissement": "PARIS"
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    )
                }
            val result = adapter.lookupBySiren(Siren("222222222"))
            assertIs<DomainResult.Ok<SireneResult>>(result)
            assertEquals(ActivityType.BNC, result.value.activityType)
        }

    @Test
    fun `lookupBySiren returns null activityType when NAF code absent`() =
        runTest {
            val adapter =
                client { _ ->
                    SireneHttpResponse(
                        200,
                        """
                        {
                          "uniteLegale": {
                            "siren": "333333333",
                            "denominationUniteLegale": "SOCIETE SANS NAF",
                            "etablissementSiege": {
                              "siret": "33333333300033",
                              "adresseEtablissement": {
                                "codePostalEtablissement": "13001",
                                "libelleCommuneEtablissement": "MARSEILLE"
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    )
                }
            val result = adapter.lookupBySiren(Siren("333333333"))
            assertIs<DomainResult.Ok<SireneResult>>(result)
            assertNull(result.value.activityType)
        }

    @Test
    fun `lookupBySiren propagates token refresh failure as SireneLookupFailed`() =
        runTest {
            val adapter =
                SireneApiClient(
                    httpExecutor = { _ -> throw mona.infrastructure.sirene.SireneTokenRefreshException("Token refresh failed: HTTP 401") },
                )
            val result = adapter.lookupBySiren(Siren("123456789"))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SireneLookupFailed>(result.error)
            assertTrue((result.error as DomainError.SireneLookupFailed).reason.contains("Token refresh failed"))
        }
}
