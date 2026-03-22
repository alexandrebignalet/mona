package mona.application.onboarding

import mona.domain.model.ActivityType
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PostalAddress
import mona.domain.model.Siren
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.CryptoPort
import mona.domain.port.SirenePort
import mona.domain.port.SireneResult
import mona.domain.port.UserRepository

sealed class SetupProfileCommand {
    abstract val userId: UserId

    data class LookupSiren(
        override val userId: UserId,
        val siren: Siren,
    ) : SetupProfileCommand()

    data class SearchSiren(
        override val userId: UserId,
        val name: String,
        val city: String,
    ) : SetupProfileCommand()

    data class ApplySireneResult(
        override val userId: UserId,
        val result: SireneResult,
    ) : SetupProfileCommand()

    data class UpdateFields(
        override val userId: UserId,
        val name: String? = null,
        val activityType: ActivityType? = null,
        val address: PostalAddress? = null,
        val defaultPaymentDelayDays: PaymentDelayDays? = null,
        val email: Email? = null,
        val declarationPeriodicity: DeclarationPeriodicity? = null,
    ) : SetupProfileCommand()

    data class SetIban(
        override val userId: UserId,
        val plainIban: String,
    ) : SetupProfileCommand()
}

sealed class SetupProfileResult {
    data class SirenFound(val user: User) : SetupProfileResult()

    data class SirenMatches(val matches: List<SireneResult>) : SetupProfileResult()

    data class Updated(val user: User) : SetupProfileResult()
}

class SetupProfile(
    private val userRepository: UserRepository,
    private val sirenePort: SirenePort,
    private val cryptoPort: CryptoPort,
) {
    suspend fun execute(command: SetupProfileCommand): DomainResult<SetupProfileResult> {
        if (command is SetupProfileCommand.SearchSiren) {
            return when (val r = sirenePort.searchByNameAndCity(command.name, command.city)) {
                is DomainResult.Err -> r
                is DomainResult.Ok -> DomainResult.Ok(SetupProfileResult.SirenMatches(r.value))
            }
        }

        val user =
            userRepository.findById(command.userId)
                ?: return DomainResult.Err(DomainError.ProfileIncomplete(listOf("user")))

        return when (command) {
            is SetupProfileCommand.LookupSiren -> {
                val sirene =
                    when (val r = sirenePort.lookupBySiren(command.siren)) {
                        is DomainResult.Err -> return r
                        is DomainResult.Ok -> r.value
                    }
                val updated =
                    user.copy(
                        siren = command.siren,
                        siret = sirene.siret,
                        name = sirene.legalName,
                        address = sirene.address ?: user.address,
                        activityType = sirene.activityType ?: user.activityType,
                    )
                userRepository.save(updated)
                DomainResult.Ok(SetupProfileResult.SirenFound(updated))
            }
            is SetupProfileCommand.ApplySireneResult -> {
                val updated =
                    user.copy(
                        siren = command.result.siren,
                        siret = command.result.siret,
                        name = command.result.legalName,
                        address = command.result.address ?: user.address,
                        activityType = command.result.activityType ?: user.activityType,
                    )
                userRepository.save(updated)
                DomainResult.Ok(SetupProfileResult.Updated(updated))
            }
            is SetupProfileCommand.UpdateFields -> {
                val updated =
                    user.copy(
                        name = command.name ?: user.name,
                        activityType = command.activityType ?: user.activityType,
                        address = command.address ?: user.address,
                        defaultPaymentDelayDays = command.defaultPaymentDelayDays ?: user.defaultPaymentDelayDays,
                        email = command.email ?: user.email,
                        declarationPeriodicity = command.declarationPeriodicity ?: user.declarationPeriodicity,
                    )
                userRepository.save(updated)
                DomainResult.Ok(SetupProfileResult.Updated(updated))
            }
            is SetupProfileCommand.SetIban -> {
                val updated = user.copy(ibanEncrypted = cryptoPort.encrypt(command.plainIban))
                userRepository.save(updated)
                DomainResult.Ok(SetupProfileResult.Updated(updated))
            }
            is SetupProfileCommand.SearchSiren -> error("unreachable")
        }
    }
}
