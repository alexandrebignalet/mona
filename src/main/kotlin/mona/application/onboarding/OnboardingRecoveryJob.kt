package mona.application.onboarding

import mona.domain.model.InvoiceStatus
import mona.domain.model.OnboardingReminderRecord
import mona.domain.model.UserId
import mona.domain.port.ConversationRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.MessageRole
import mona.domain.port.MessagingPort
import mona.domain.port.OnboardingReminderRepository
import mona.domain.port.UserRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

private const val REMINDER_1 =
    "Salut ! Tu as un brouillon de facture en attente — tu veux le finaliser ?\n" +
        "Il me manque juste ton SIREN pour générer la facture définitive."

private const val REMINDER_2 =
    "Hey ! Ton brouillon est toujours en attente.\n" +
        "Si tu connais pas ton SIREN, dis-moi juste ton nom et ta ville — je le retrouve pour toi ✓"

class OnboardingRecoveryJob(
    private val userRepository: UserRepository,
    private val invoiceRepository: InvoiceRepository,
    private val reminderRepository: OnboardingReminderRepository,
    private val conversationRepository: ConversationRepository,
    private val messagingPort: MessagingPort,
) {
    suspend fun execute(today: LocalDate) {
        for (user in userRepository.findAllWithoutSiren()) {
            runCatching { processUser(user.id, today) }
        }
    }

    private suspend fun processUser(
        userId: UserId,
        today: LocalDate,
    ) {
        val drafts = invoiceRepository.findByUserAndStatus(userId, InvoiceStatus.Draft)
        if (drafts.isEmpty()) return

        val oldestCreatedDate =
            drafts
                .minByOrNull { it.createdAt }
                ?.createdAt
                ?.atZone(PARIS)
                ?.toLocalDate()
                ?: return

        val daysOld = today.toEpochDay() - oldestCreatedDate.toEpochDay()
        val record = reminderRepository.findByUser(userId) ?: OnboardingReminderRecord(userId)

        if (daysOld >= 1L && record.r1SentAt == null) {
            messagingPort.sendMessage(userId, REMINDER_1)
            reminderRepository.save(record.copy(r1SentAt = Instant.now()))
            return
        }

        if (daysOld >= 3L && record.r1SentAt != null && record.r2SentAt == null) {
            if (!isAcknowledged(userId, record.r1SentAt!!)) {
                messagingPort.sendMessage(userId, REMINDER_2)
                reminderRepository.save(record.copy(r2SentAt = Instant.now()))
            }
        }
    }

    private suspend fun isAcknowledged(
        userId: UserId,
        since: Instant,
    ): Boolean {
        val recent = conversationRepository.findRecent(userId, 3)
        return recent.any { it.role == MessageRole.USER && it.createdAt.isAfter(since) }
    }
}
