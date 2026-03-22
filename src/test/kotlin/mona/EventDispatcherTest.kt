package mona

import kotlinx.coroutines.runBlocking
import mona.application.EventDispatcher
import mona.domain.model.DomainEvent
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class EventDispatcherTest {
    private val event1 =
        DomainEvent.DraftDeleted(
            invoiceId = InvoiceId("inv-1"),
            invoiceNumber = InvoiceNumber("F-2026-03-001"),
            userId = UserId("user-1"),
            occurredAt = Instant.parse("2026-03-22T10:00:00Z"),
        )
    private val event2 =
        DomainEvent.DraftDeleted(
            invoiceId = InvoiceId("inv-2"),
            invoiceNumber = InvoiceNumber("F-2026-03-002"),
            userId = UserId("user-1"),
            occurredAt = Instant.parse("2026-03-22T11:00:00Z"),
        )

    @Test
    fun `dispatch calls all registered handlers for each event`() {
        runBlocking {
            val dispatcher = EventDispatcher()
            val received1 = mutableListOf<DomainEvent>()
            val received2 = mutableListOf<DomainEvent>()

            dispatcher.register { received1.add(it) }
            dispatcher.register { received2.add(it) }

            dispatcher.dispatch(listOf(event1, event2))

            assertEquals(listOf(event1, event2), received1)
            assertEquals(listOf(event1, event2), received2)
        }
    }

    @Test
    fun `handlers are called in registration order`() {
        runBlocking {
            val dispatcher = EventDispatcher()
            val callOrder = mutableListOf<Int>()

            dispatcher.register { callOrder.add(1) }
            dispatcher.register { callOrder.add(2) }
            dispatcher.register { callOrder.add(3) }

            dispatcher.dispatch(listOf(event1))

            assertEquals(listOf(1, 2, 3), callOrder)
        }
    }

    @Test
    fun `dispatch with no handlers does nothing`() {
        runBlocking {
            val dispatcher = EventDispatcher()
            dispatcher.dispatch(listOf(event1, event2))
        }
    }

    @Test
    fun `dispatch with empty event list calls no handlers`() {
        runBlocking {
            val dispatcher = EventDispatcher()
            var called = false
            dispatcher.register { called = true }

            dispatcher.dispatch(emptyList())

            assertEquals(false, called)
        }
    }
}
