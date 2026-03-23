package mona.application.settings

import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.PaymentDelayDays
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.UserRepository

data class ConfigureSettingCommand(
    val userId: UserId,
    val setting: String,
    val value: String,
)

class ConfigureSetting(
    private val userRepository: UserRepository,
) {
    suspend fun execute(command: ConfigureSettingCommand): DomainResult<User> {
        val user =
            userRepository.findById(command.userId)
                ?: return DomainResult.Err(DomainError.ProfileIncomplete(listOf("user")))

        val updated =
            when (command.setting) {
                "confirm_before_create" -> {
                    val on = command.value.lowercase() in listOf("true", "oui", "1", "yes")
                    user.copy(confirmBeforeCreate = on)
                }
                "default_payment_delay_days" -> {
                    val days =
                        command.value.toIntOrNull()?.coerceIn(1, 60)
                            ?: return DomainResult.Err(DomainError.InvalidSettingValue(command.setting, command.value))
                    user.copy(defaultPaymentDelayDays = PaymentDelayDays(days))
                }
                else -> return DomainResult.Err(DomainError.UnknownSetting(command.setting))
            }

        userRepository.save(updated)
        return DomainResult.Ok(updated)
    }
}
