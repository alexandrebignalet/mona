package mona.application.urssaf

import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.UrssafReminderRecord
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.ConversationRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.MessageRole
import mona.domain.port.MessagingPort
import mona.domain.port.UrssafReminderRepository
import mona.domain.port.UserRepository
import mona.domain.service.RevenueBreakdown
import mona.domain.service.RevenueCalculation
import mona.domain.service.UrssafThresholds
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val URSSAF_PORTAL = "https://autoentrepreneur.urssaf.fr"

class UrssafReminderJob(
    private val userRepository: UserRepository,
    private val invoiceRepository: InvoiceRepository,
    private val reminderRepository: UrssafReminderRepository,
    private val conversationRepository: ConversationRepository,
    private val messagingPort: MessagingPort,
) {
    suspend fun execute(today: LocalDate) {
        for (user in userRepository.findAllWithPeriodicity()) {
            runCatching { processUser(user, today) }
        }
    }

    private suspend fun processUser(
        user: User,
        today: LocalDate,
    ) {
        val periodicity = user.declarationPeriodicity ?: return
        // Use today-1month as ref so nextDeclarationDeadline yields the approaching deadline
        val deadline = UrssafThresholds.nextDeclarationDeadline(periodicity, today.minusMonths(1))
        val daysUntil = ChronoUnit.DAYS.between(today, deadline)
        if (daysUntil != 7L && daysUntil != 1L) return

        val periodKey = computePeriodKey(periodicity, deadline)
        val record =
            reminderRepository.findByUserAndPeriod(user.id, periodKey)
                ?: UrssafReminderRecord(user.id, periodKey)
        val period = computeDeclarationPeriod(periodicity, deadline)
        val label = periodLabel(periodicity, deadline)

        if (daysUntil == 7L && record.d7SentAt == null) {
            val breakdown = loadRevenue(user.id, period)
            messagingPort.sendMessage(user.id, formatReminder(user, deadline, 7, label, breakdown))
            reminderRepository.save(record.copy(d7SentAt = Instant.now()))
        } else if (daysUntil == 1L && record.d1SentAt == null && !isAcknowledged(user.id, record.d7SentAt)) {
            val breakdown = loadRevenue(user.id, period)
            messagingPort.sendMessage(user.id, formatReminder(user, deadline, 1, label, breakdown))
            reminderRepository.save(record.copy(d1SentAt = Instant.now()))
        }
    }

    private suspend fun loadRevenue(
        userId: UserId,
        period: DeclarationPeriod,
    ): RevenueBreakdown {
        val snapshots = invoiceRepository.findPaidInPeriod(userId, period)
        val creditNotes = invoiceRepository.findCreditNotesInPeriod(userId, period)
        return RevenueCalculation.compute(snapshots, creditNotes)
    }

    private suspend fun isAcknowledged(
        userId: UserId,
        d7SentAt: Instant?,
    ): Boolean {
        if (d7SentAt == null) return false
        val recent = conversationRepository.findRecent(userId, 3)
        return recent.any { it.role == MessageRole.USER && it.createdAt.isAfter(d7SentAt) }
    }

    private fun formatReminder(
        user: User,
        deadline: LocalDate,
        daysUntil: Int,
        periodLabel: String,
        breakdown: RevenueBreakdown,
    ): String {
        val firstName = user.name?.split(" ")?.firstOrNull() ?: "toi"
        val deadlineStr = deadline.format(DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH))
        val header =
            if (daysUntil == 7) {
                "🗓 Hey $firstName ! Ta déclaration URSSAF est dans 7 jours ($deadlineStr)."
            } else {
                "⏰ Hey $firstName ! Ta déclaration URSSAF est demain ($deadlineStr)."
            }
        val revenueLines =
            if (breakdown.byActivity.size <= 1) {
                "CA à déclarer ce $periodLabel : ${formatEuros(breakdown.total)}"
            } else {
                val lines =
                    breakdown.byActivity.entries.joinToString("\n") { (activity, amount) ->
                        "→ ${activityLabel(activity)} : ${formatEuros(amount)}"
                    }
                "CA à déclarer ce $periodLabel :\n$lines"
            }
        val link = "👉 $URSSAF_PORTAL"
        val footer = if (daysUntil == 7) "Réponds OK une fois déclaré ✓" else null
        return listOfNotNull(header, revenueLines, link, footer).joinToString("\n")
    }

    private fun computePeriodKey(
        periodicity: DeclarationPeriodicity,
        deadline: LocalDate,
    ): String {
        val periodEndMonth = deadline.minusMonths(1)
        return when (periodicity) {
            DeclarationPeriodicity.MONTHLY ->
                "${periodEndMonth.year}-${periodEndMonth.monthValue.toString().padStart(2, '0')}"
            DeclarationPeriodicity.QUARTERLY -> {
                val quarter = (periodEndMonth.monthValue - 1) / 3 + 1
                "${periodEndMonth.year}-Q$quarter"
            }
        }
    }

    private fun computeDeclarationPeriod(
        periodicity: DeclarationPeriodicity,
        deadline: LocalDate,
    ): DeclarationPeriod {
        val periodEndDate = deadline.minusMonths(1)
        val periodEnd = periodEndDate.withDayOfMonth(periodEndDate.lengthOfMonth())
        val periodStart =
            when (periodicity) {
                DeclarationPeriodicity.MONTHLY -> periodEnd.withDayOfMonth(1)
                DeclarationPeriodicity.QUARTERLY -> periodEnd.minusMonths(2).withDayOfMonth(1)
            }
        return DeclarationPeriod(periodStart, periodEnd)
    }

    private fun periodLabel(
        periodicity: DeclarationPeriodicity,
        deadline: LocalDate,
    ): String {
        val periodEndMonth = deadline.minusMonths(1)
        return when (periodicity) {
            DeclarationPeriodicity.MONTHLY -> {
                val monthName =
                    periodEndMonth.month.getDisplayName(TextStyle.FULL, Locale.FRENCH).lowercase()
                "mois de $monthName"
            }
            DeclarationPeriodicity.QUARTERLY -> {
                val quarter = (periodEndMonth.monthValue - 1) / 3 + 1
                when (quarter) {
                    1 -> "1er trimestre"
                    2 -> "2e trimestre"
                    3 -> "3e trimestre"
                    else -> "4e trimestre"
                }
            }
        }
    }

    private fun activityLabel(activity: ActivityType): String =
        when (activity) {
            ActivityType.BNC -> "Professions libérales (BNC)"
            ActivityType.BIC_SERVICE -> "Prestations de services (BIC)"
            ActivityType.BIC_VENTE -> "Vente de marchandises (BIC)"
        }

    private fun formatEuros(cents: Cents): String {
        val euros = cents.value / 100
        return "${NumberFormat.getIntegerInstance(Locale.FRENCH).format(euros)}€"
    }
}
