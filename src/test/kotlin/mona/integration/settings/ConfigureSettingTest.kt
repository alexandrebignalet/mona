package mona.integration.settings

import kotlinx.coroutines.runBlocking
import mona.application.settings.ConfigureSetting
import mona.application.settings.ConfigureSettingCommand
import mona.domain.model.DomainResult
import mona.domain.model.PaymentDelayDays
import mona.domain.model.User
import mona.integration.IntegrationTestBase
import mona.integration.TEST_USER_ID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigureSettingTest : IntegrationTestBase() {
    private fun useCase() = ConfigureSetting(userRepo)

    @BeforeTest
    fun seedUser() =
        setup {
            createTestUser()
        }

    @Test
    fun `set confirm_before_create to true persists change`() =
        runBlocking {
            val result =
                useCase().execute(
                    ConfigureSettingCommand(TEST_USER_ID, "confirm_before_create", "true"),
                )
            assertIs<DomainResult.Ok<User>>(result)
            assertTrue(result.value.confirmBeforeCreate)
            val saved = userRepo.findById(TEST_USER_ID)
            assertTrue(saved?.confirmBeforeCreate == true)
        }

    @Test
    fun `set confirm_before_create to false persists change`() =
        runBlocking {
            // First set to true
            useCase().execute(ConfigureSettingCommand(TEST_USER_ID, "confirm_before_create", "true"))
            // Then disable
            val result =
                useCase().execute(
                    ConfigureSettingCommand(TEST_USER_ID, "confirm_before_create", "false"),
                )
            assertIs<DomainResult.Ok<User>>(result)
            assertTrue(!result.value.confirmBeforeCreate)
            val saved = userRepo.findById(TEST_USER_ID)
            assertTrue(saved?.confirmBeforeCreate == false)
        }

    @Test
    fun `set default_payment_delay_days persists new value`() =
        runBlocking {
            val result =
                useCase().execute(
                    ConfigureSettingCommand(TEST_USER_ID, "default_payment_delay_days", "45"),
                )
            assertIs<DomainResult.Ok<User>>(result)
            assertEquals(PaymentDelayDays(45), result.value.defaultPaymentDelayDays)
            val saved = userRepo.findById(TEST_USER_ID)
            assertEquals(PaymentDelayDays(45), saved?.defaultPaymentDelayDays)
        }
}
