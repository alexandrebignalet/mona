package mona.domain.port

import mona.domain.model.ActivityType
import mona.domain.model.DomainResult
import mona.domain.model.PostalAddress
import mona.domain.model.Siren
import mona.domain.model.Siret

data class SireneResult(
    val legalName: String,
    val siren: Siren,
    val siret: Siret,
    val address: PostalAddress?,
    val activityType: ActivityType?,
)

interface SirenePort {
    suspend fun lookupBySiren(siren: Siren): DomainResult<SireneResult>

    suspend fun searchByNameAndCity(
        name: String,
        city: String,
    ): DomainResult<List<SireneResult>>
}
