package mona.domain

import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.DeclarationPeriodicity
import mona.domain.service.UrssafThresholds
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UrssafThresholdsTest {
    @Test
    fun `no alert below 80 percent for BNC`() {
        // 36,800 EUR threshold, 80% = 29,440 EUR = 2_944_000 cents
        val result = UrssafThresholds.checkTvaThreshold(Cents(2_940_000), ActivityType.BNC)
        assertNull(result)
    }

    @Test
    fun `alert at 80 percent for BNC`() {
        // 80% of 3,680,000 = 2,944,000
        val result = UrssafThresholds.checkTvaThreshold(Cents(2_944_000), ActivityType.BNC)
        assertNotNull(result)
        assertEquals(80, result.percentReached)
    }

    @Test
    fun `alert at 95 percent for BIC_VENTE`() {
        // 91,900 EUR = 9,190,000 cents, 95% = 8,730,500
        val result = UrssafThresholds.checkTvaThreshold(Cents(8_731_000), ActivityType.BIC_VENTE)
        assertNotNull(result)
        assertEquals(95, result.percentReached)
    }

    @Test
    fun `BIC_SERVICE uses 36800 threshold`() {
        val result = UrssafThresholds.checkTvaThreshold(Cents(3_680_000), ActivityType.BIC_SERVICE)
        assertNotNull(result)
        assertEquals(100, result.percentReached)
    }

    @Test
    fun `monthly deadline is end of following month`() {
        val ref = LocalDate.of(2026, 1, 15)
        val deadline = UrssafThresholds.nextDeclarationDeadline(DeclarationPeriodicity.MONTHLY, ref)
        assertEquals(LocalDate.of(2026, 2, 28), deadline)
    }

    @Test
    fun `quarterly deadline Q1 is end of April`() {
        val ref = LocalDate.of(2026, 2, 15)
        val deadline = UrssafThresholds.nextDeclarationDeadline(DeclarationPeriodicity.QUARTERLY, ref)
        assertEquals(LocalDate.of(2026, 4, 30), deadline)
    }

    @Test
    fun `quarterly deadline Q4 is end of January next year`() {
        val ref = LocalDate.of(2026, 11, 1)
        val deadline = UrssafThresholds.nextDeclarationDeadline(DeclarationPeriodicity.QUARTERLY, ref)
        assertEquals(LocalDate.of(2027, 1, 31), deadline)
    }
}
