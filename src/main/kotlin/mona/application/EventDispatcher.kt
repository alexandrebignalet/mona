package mona.application

import mona.domain.model.DomainEvent

class EventDispatcher {
    private val handlers = mutableListOf<suspend (DomainEvent) -> Unit>()

    fun register(handler: suspend (DomainEvent) -> Unit) {
        handlers.add(handler)
    }

    suspend fun dispatch(events: List<DomainEvent>) {
        for (event in events) {
            for (handler in handlers) {
                handler(event)
            }
        }
    }
}
