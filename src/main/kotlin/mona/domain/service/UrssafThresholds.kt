package mona.domain.service

import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.DeclarationPeriodicity
import java.time.LocalDate

data class ThresholdAlert(
    val currentRevenue: Cents,
    val threshold: Cents,
    val activityType: ActivityType,
    val percentReached: Int,
)

object UrssafThresholds {
    private val TVA_THRESHOLDS =
        mapOf(
            ActivityType.BIC_VENTE to Cents(91_900_00),
            ActivityType.BIC_SERVICE to Cents(36_800_00),
            ActivityType.BNC to Cents(36_800_00),
        )

    fun checkTvaThreshold(
        revenue: Cents,
        activityType: ActivityType,
    ): ThresholdAlert? {
        val threshold = TVA_THRESHOLDS[activityType] ?: return null
        val pct = if (threshold.value > 0) ((revenue.value * 100) / threshold.value).toInt() else 0
        return if (pct >= 80) ThresholdAlert(revenue, threshold, activityType, pct) else null
    }

    fun nextDeclarationDeadline(
        periodicity: DeclarationPeriodicity,
        ref: LocalDate,
    ): LocalDate =
        when (periodicity) {
            DeclarationPeriodicity.MONTHLY -> {
                val m = ref.plusMonths(1)
                m.withDayOfMonth(m.lengthOfMonth())
            }
            DeclarationPeriodicity.QUARTERLY -> {
                val qEnd =
                    when (ref.monthValue) {
                        in 1..3 -> LocalDate.of(ref.year, 3, 31)
                        in 4..6 -> LocalDate.of(ref.year, 6, 30)
                        in 7..9 -> LocalDate.of(ref.year, 9, 30)
                        else -> LocalDate.of(ref.year, 12, 31)
                    }
                val d = qEnd.plusMonths(1)
                d.withDayOfMonth(d.lengthOfMonth())
            }
        }
}
