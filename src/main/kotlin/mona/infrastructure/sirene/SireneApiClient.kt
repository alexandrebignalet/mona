package mona.infrastructure.sirene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mona.domain.model.ActivityType
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.PostalAddress
import mona.domain.model.Siren
import mona.domain.model.Siret
import mona.domain.port.SirenePort
import mona.domain.port.SireneResult
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets

internal data class SireneHttpResponse(val statusCode: Int, val body: String)

internal fun interface SireneHttpExecutor {
    suspend fun get(url: String): SireneHttpResponse
}

@JvmInline
internal value class SireneApiKey(val value: String)

internal class RealSireneHttpExecutor(
    private val sireneApiKey: SireneApiKey,
) : SireneHttpExecutor {
    private val client: HttpClient = HttpClient.newHttpClient()

    override suspend fun get(url: String): SireneHttpResponse {
        return withContext(Dispatchers.IO) {
            val request =
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-INSEE-Api-Key-Integration", sireneApiKey.value)
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            val response = client.send(request, BodyHandlers.ofString())
            SireneHttpResponse(response.statusCode(), response.body())
        }
    }
}

class SireneApiClient internal constructor(
    private val httpExecutor: SireneHttpExecutor,
    private val baseUrl: String = BASE_URL,
) : SirenePort {
    private val log = LoggerFactory.getLogger(SireneApiClient::class.java)

    companion object {
        const val BASE_URL = "https://api.insee.fr/api-sirene/3.11/"

        private val json = Json { ignoreUnknownKeys = true }

        fun fromEnv(): SireneApiClient {
            val sireneApiKey =
                SireneApiKey(
                    System.getenv("SIRENE_API_KEY") ?: error("SIRENE_API_KEY environment variable is not set"),
                )

            return SireneApiClient(httpExecutor = RealSireneHttpExecutor(sireneApiKey))
        }
    }

    override suspend fun lookupBySiren(siren: Siren): DomainResult<SireneResult> =
        try {
            val url = "$baseUrl/siren/${siren.value}"
            val response = httpExecutor.get(url)
            when {
                response.statusCode == 200 -> parseSirenResponse(response.body, siren)
                response.statusCode == 404 -> DomainResult.Err(DomainError.SirenNotFound(siren))
                else -> {
                    // L10
                    log.warn("SIRENE lookup failed for {}: HTTP {}", siren.value, response.statusCode)
                    DomainResult.Err(DomainError.SireneLookupFailed("HTTP ${response.statusCode}: ${response.body}"))
                }
            }
        } catch (e: SireneTokenRefreshException) {
            DomainResult.Err(DomainError.SireneLookupFailed(e.message ?: "Token refresh failed"))
        }

    override suspend fun searchByNameAndCity(
        name: String,
        city: String,
    ): DomainResult<List<SireneResult>> =
        try {
            val rawQuery =
                "(denominationUniteLegale:\"$name\"* OR nomUniteLegale:\"$name\"*) " +
                    "AND libelleCommuneEtablissement:\"$city\"* " +
                    "AND etablissementSiege:true"
            val encodedQuery = URLEncoder.encode(rawQuery, StandardCharsets.UTF_8)
            val url = "$baseUrl/siret?q=$encodedQuery&nombre=5"
            val response = httpExecutor.get(url)
            when {
                response.statusCode == 200 -> parseSearchResponse(response.body)
                response.statusCode == 404 -> DomainResult.Ok(emptyList())
                else -> DomainResult.Err(DomainError.SireneLookupFailed("HTTP ${response.statusCode}: ${response.body}"))
            }
        } catch (e: SireneTokenRefreshException) {
            DomainResult.Err(DomainError.SireneLookupFailed(e.message ?: "Token refresh failed"))
        }

    private fun parseSirenResponse(
        body: String,
        requestedSiren: Siren,
    ): DomainResult<SireneResult> =
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val uniteLegale =
                root["uniteLegale"]?.jsonObject
                    ?: return DomainResult.Err(DomainError.SireneLookupFailed("Missing uniteLegale in response"))
            val sirenValue = uniteLegale["siren"]?.jsonPrimitive?.content ?: requestedSiren.value
            val legalName = extractLegalName(uniteLegale)
            // NAF code and NIC come from the most recent period (index 0)
            val currentPeriod = uniteLegale["periodesUniteLegale"]?.jsonArray?.firstOrNull()?.jsonObject
            val nafCode = currentPeriod?.get("activitePrincipaleUniteLegale")?.jsonPrimitive?.content
            val nicSiege = currentPeriod?.get("nicSiegeUniteLegale")?.jsonPrimitive?.content
                ?: return DomainResult.Err(DomainError.SireneLookupFailed("Missing nicSiegeUniteLegale in response"))
            val siretValue = sirenValue + nicSiege
            DomainResult.Ok(
                SireneResult(
                    legalName = legalName,
                    siren = Siren(sirenValue),
                    siret = Siret(siretValue),
                    address = null, // /siren endpoint does not return address; use /siret if needed
                    activityType = nafCode?.let { nafToActivityType(it) },
                ),
            )
        } catch (e: Exception) {
            DomainResult.Err(DomainError.SireneLookupFailed("Parse error: ${e.message}"))
        }

    private fun parseSearchResponse(body: String): DomainResult<List<SireneResult>> =
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val etablissements =
                root["etablissements"]?.jsonArray
                    ?: return DomainResult.Ok(emptyList())
            val results =
                etablissements.mapNotNull { elem ->
                    try {
                        val etab = elem.jsonObject
                        val siretValue = etab["siret"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val ul = etab["uniteLegale"]?.jsonObject ?: return@mapNotNull null
                        val legalName = extractLegalName(ul)
                        val nafCode = ul["activitePrincipaleUniteLegale"]?.jsonPrimitive?.content
                        val address = etab["adresseEtablissement"]?.jsonObject?.let { parseAddress(it) }
                        SireneResult(
                            legalName = legalName,
                            siren = Siren(siretValue.take(9)),
                            siret = Siret(siretValue),
                            address = address,
                            activityType = nafCode?.let { nafToActivityType(it) },
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
            DomainResult.Ok(results)
        } catch (e: Exception) {
            DomainResult.Err(DomainError.SireneLookupFailed("Parse error: ${e.message}"))
        }

    private fun extractLegalName(uniteLegale: JsonObject): String {
        val denomination = uniteLegale["denominationUniteLegale"]?.jsonPrimitive?.content
        if (!denomination.isNullOrBlank()) return denomination
        val nom = uniteLegale["nomUniteLegale"]?.jsonPrimitive?.content.orEmpty()
        val prenom = uniteLegale["prenomUsuelUniteLegale"]?.jsonPrimitive?.content.orEmpty()
        return buildString {
            if (prenom.isNotBlank()) append("$prenom ")
            append(nom)
        }.trim().ifBlank { "Inconnu" }
    }

    private fun parseAddress(adresse: JsonObject): PostalAddress? {
        val postalCode = adresse["codePostalEtablissement"]?.jsonPrimitive?.content ?: return null
        val city = adresse["libelleCommuneEtablissement"]?.jsonPrimitive?.content ?: return null
        val numeroRaw = adresse["numeroVoieEtablissement"]?.jsonPrimitive?.content
        val numero = if (!numeroRaw.isNullOrBlank()) "$numeroRaw " else ""
        val typeVoie = adresse["typeVoieEtablissement"]?.jsonPrimitive?.content.orEmpty()
        val libelleVoie = adresse["libelleVoieEtablissement"]?.jsonPrimitive?.content.orEmpty()
        val street = "$numero$typeVoie $libelleVoie".trim()
        return PostalAddress(
            street = street.ifBlank { "Non renseignée" },
            postalCode = postalCode,
            city = city,
        )
    }

    private fun nafToActivityType(nafCode: String): ActivityType =
        when {
            nafCode.startsWith("69.1") -> ActivityType.BNC
            nafCode.startsWith("69.2") -> ActivityType.BNC
            nafCode.startsWith("71.") -> ActivityType.BNC
            nafCode.startsWith("72.") -> ActivityType.BNC
            nafCode.startsWith("74.90") -> ActivityType.BNC
            nafCode.startsWith("75.") -> ActivityType.BNC
            nafCode.startsWith("85.") -> ActivityType.BNC
            nafCode.startsWith("86.") -> ActivityType.BNC
            nafCode.startsWith("87.") -> ActivityType.BNC
            nafCode.startsWith("88.") -> ActivityType.BNC
            else -> {
                val prefix = nafCode.replace(".", "").take(2).toIntOrNull() ?: return ActivityType.BIC_SERVICE
                when (prefix) {
                    in 1..39 -> ActivityType.BIC_VENTE
                    in 45..47 -> ActivityType.BIC_VENTE
                    else -> ActivityType.BIC_SERVICE
                }
            }
        }
}
