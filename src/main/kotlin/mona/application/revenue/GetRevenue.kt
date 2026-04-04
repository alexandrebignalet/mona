package mona.application.revenue

import mona.domain.model.Cents
import mona.domain.model.DeclarationPeriod
import mona.domain.model.InvoiceStatus
import mona.domain.model.UserId
import mona.domain.port.InvoiceRepository
import mona.domain.service.RevenueBreakdown
import mona.domain.service.RevenueCalculation

data class GetRevenueCommand(
    val userId: UserId,
    val period: DeclarationPeriod,
    val periodType: String,
)

data class GetRevenueResult(
    val breakdown: RevenueBreakdown,
    val paidCount: Int,
    val pendingCount: Int,
    val pendingAmount: Cents,
    val period: DeclarationPeriod,
    val periodType: String,
)

class GetRevenue(
    private val invoiceRepository: InvoiceRepository,
) {
    suspend fun execute(command: GetRevenueCommand): GetRevenueResult {
        val paidSnapshots = invoiceRepository.findPaidInPeriod(command.userId, command.period)
        val creditNoteSnapshots = invoiceRepository.findCreditNotesInPeriod(command.userId, command.period)
        val breakdown = RevenueCalculation.compute(paidSnapshots, creditNoteSnapshots)

        val sent = invoiceRepository.findByUserAndStatus(command.userId, InvoiceStatus.Sent)
        val overdue = invoiceRepository.findByUserAndStatus(command.userId, InvoiceStatus.Overdue)
        val pending = sent + overdue
        val pendingAmount = pending.fold(Cents.ZERO) { acc, inv -> acc + inv.amountHt }

        return GetRevenueResult(
            breakdown = breakdown,
            paidCount = paidSnapshots.size,
            pendingCount = pending.size,
            pendingAmount = pendingAmount,
            period = command.period,
            periodType = command.periodType,
        )
    }
}
