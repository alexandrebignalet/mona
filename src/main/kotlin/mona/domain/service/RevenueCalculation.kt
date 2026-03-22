package mona.domain.service

import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.PaidInvoiceSnapshot

data class RevenueBreakdown(
    val total: Cents,
    val byActivity: Map<ActivityType, Cents>,
)

object RevenueCalculation {
    fun compute(
        paidInvoices: List<PaidInvoiceSnapshot>,
        creditNotes: List<CreditNoteSnapshot>,
    ): RevenueBreakdown {
        val allActivities =
            (paidInvoices.map { it.activityType } + creditNotes.map { it.activityType }).toSet()
        val byActivity =
            allActivities.associateWith { activity ->
                val revenue =
                    Cents(
                        paidInvoices.filter { it.activityType == activity }.sumOf { it.amountHt.value },
                    )
                val offset =
                    Cents(
                        creditNotes.filter { it.activityType == activity }.sumOf { it.amountHt.value },
                    )
                revenue + offset
            }
        return RevenueBreakdown(Cents(byActivity.values.sumOf { it.value }), byActivity)
    }
}
