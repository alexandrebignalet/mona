package mona.domain.port

import mona.domain.model.User
import mona.domain.model.UserId

interface UserRepository {
    suspend fun findById(id: UserId): User?

    suspend fun findByTelegramId(telegramId: Long): User?

    suspend fun save(user: User)

    suspend fun findAllWithPeriodicity(): List<User>
}
