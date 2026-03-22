package mona.domain.model

import java.time.Instant

data class Client(
    val id: ClientId,
    val userId: UserId,
    val name: String,
    val email: Email?,
    val address: PostalAddress?,
    val companyName: String?,
    val siret: Siret?,
    val createdAt: Instant,
)
