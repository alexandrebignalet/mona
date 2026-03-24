package mona.application.urssaf

import mona.domain.model.Cents
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DomainEvent
import mona.domain.model.VatAlertRecord
import mona.domain.port.InvoiceRepository
import mona.domain.port.MessagingPort
import mona.domain.port.VatAlertRepository
import mona.domain.service.RevenueCalculation
import mona.domain.service.UrssafThresholds
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class CheckVatThreshold(
    private val invoiceRepository: InvoiceRepository,
    private val vatAlertRepository: VatAlertRepository,
    private val messagingPort: MessagingPort,
) {
    suspend fun execute(event: DomainEvent.InvoicePaid) {
        val year = event.paidDate.year
        val period = DeclarationPeriod(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))

        val snapshots = invoiceRepository.findPaidInPeriod(event.userId, period)
        val creditNotes = invoiceRepository.findCreditNotesInPeriod(event.userId, period)
        val breakdown = RevenueCalculation.compute(snapshots, creditNotes)
        val revenue = breakdown.byActivity[event.activityType] ?: Cents.ZERO

        val alert = UrssafThresholds.checkTvaThreshold(revenue, event.activityType) ?: return

        val yearKey = "$year-${event.activityType.name}"
        val record =
            vatAlertRepository.findByUserAndYear(event.userId, yearKey)
                ?: VatAlertRecord(event.userId, yearKey)

        val pct = alert.percentReached
        val thresholdEuros = NumberFormat.getInstance(Locale.FRENCH).format(alert.threshold.value / 100)
        val now = Instant.now()

        var updated = record

        if (pct >= 95 && record.p95SentAt == null) {
            val msg =
                "⚠️ Attention ! Tu es à $pct% du seuil de franchise TVA ($thresholdEuros €). " +
                    "Tu risques de le dépasser — pense à consulter un comptable."
            messagingPort.sendMessage(event.userId, msg)
            updated = updated.copy(p95SentAt = now, p80SentAt = updated.p80SentAt ?: now)
        } else if (pct >= 80 && record.p80SentAt == null) {
            val msg =
                "📊 Tu approches du seuil TVA : $pct% atteint (seuil : $thresholdEuros €). " +
                    "Garde un œil sur ton CA !"
            messagingPort.sendMessage(event.userId, msg)
            updated = updated.copy(p80SentAt = now)
        }

        if (updated != record) {
            vatAlertRepository.save(updated)
        }
    }
}
