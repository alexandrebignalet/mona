package mona.application.gdpr

import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.ConversationRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.UserRepository

class DeleteAccount(
    private val userRepository: UserRepository,
    private val clientRepository: ClientRepository,
    private val conversationRepository: ConversationRepository,
    private val invoiceRepository: InvoiceRepository,
) {
    suspend fun execute(userId: UserId) {
        invoiceRepository.anonymizeByUser(userId)
        clientRepository.deleteByUser(userId)
        conversationRepository.deleteByUser(userId)
        userRepository.delete(userId)
    }
}
