# CQRS Framework — Technical Specification

This document specifies a generic, suspend-native CQRS framework as a separate Gradle module (`cqrs/`). The framework provides command/query buses with middleware chains and built-in event dispatching. It has **zero dependency on Mona's domain** and is reusable across projects.

---

## 1. Motivation

The application layer currently has ~20 use cases as standalone classes. Each command use case takes an `EventDispatcher` as a constructor parameter and calls `eventDispatcher.dispatch(events)` inline after persisting. This couples event dispatching to every use case and makes it impossible to add cross-cutting concerns (logging, monitoring, audit trail) without modifying each class individually.

The CQRS framework formalizes:
- **Command/Query split** — commands produce side effects and return domain events; queries are read-only
- **Middleware chains** — ordered interceptors that wrap handler execution (before/after pattern)
- **Event dispatching as middleware** — extracted from use cases into a reusable middleware, enabling future audit log and event listener capabilities

---

## 2. Module Structure

```
cqrs/
├── build.gradle.kts
└── src/
    ├── main/kotlin/cqrs/
    │   ├── Command.kt
    │   ├── Query.kt
    │   ├── CommandResponse.kt
    │   ├── CommandHandler.kt
    │   ├── QueryHandler.kt
    │   ├── CommandMiddleware.kt
    │   ├── QueryMiddleware.kt
    │   ├── CommandBus.kt
    │   ├── QueryBus.kt
    │   ├── EventDispatcherMiddleware.kt
    │   └── CqrsDsl.kt
    └── test/kotlin/cqrs/
        ├── CommandBusTest.kt
        ├── QueryBusTest.kt
        └── EventDispatcherMiddlewareTest.kt
```

### Dependencies

| Dependency | Purpose |
|-----------|---------|
| `kotlinx-coroutines-core` | Suspend-native handlers and middleware |
| `kotlin-test` (test only) | Unit tests |
| `kotlinx-coroutines-test` (test only) | `runTest` for coroutine tests |

No other dependencies. No reflection libraries, no annotation processing, no classpath scanning.

---

## 3. Core Types

### 3.1 Marker Interfaces

```kotlin
// Command.kt
interface Command<R>

// Query.kt
interface Query<R>
```

Type parameter `R` carries the result type. In the host app, `R` will typically be `DomainResult<XxxResult>` — the framework does not know or care about `DomainResult`.

### 3.2 CommandResponse

```kotlin
// CommandResponse.kt
data class CommandResponse<R>(
    val result: R,
    val events: List<Any> = emptyList(),
)
```

Events are `List<Any>` because the framework is generic. The host app's `EventDispatcherMiddleware` listeners downcast to their domain event types.

### 3.3 Handlers

```kotlin
// CommandHandler.kt
interface CommandHandler<C : Command<R>, R> {
    suspend fun handle(command: C): CommandResponse<R>
}

// QueryHandler.kt
interface QueryHandler<Q : Query<R>, R> {
    suspend fun handle(query: Q): R
}
```

Each existing use case will implement one of these interfaces. The `execute(command)` method becomes `handle(command)`.

---

## 4. Middleware

### 4.1 Interfaces

```kotlin
// CommandMiddleware.kt
interface CommandMiddleware {
    suspend fun <R> handle(
        command: Command<R>,
        next: suspend () -> CommandResponse<R>,
    ): CommandResponse<R>
}

// QueryMiddleware.kt
interface QueryMiddleware {
    suspend fun <R> handle(
        query: Query<R>,
        next: suspend () -> R,
    ): R
}
```

Each middleware receives the command/query and a `next` lambda. It **must** call `next()` to proceed down the chain (or short-circuit by returning early). This allows before/after logic:

```kotlin
class LoggingMiddleware : CommandMiddleware {
    override suspend fun <R> handle(
        command: Command<R>,
        next: suspend () -> CommandResponse<R>,
    ): CommandResponse<R> {
        println("Before: ${command::class.simpleName}")
        val response = next()
        println("After: ${command::class.simpleName}, events=${response.events.size}")
        return response
    }
}
```

### 4.2 Execution Order

Middleware executes in **registration order**. Given middlewares `[M1, M2]` and handler `H`:

```
M1.before → M2.before → H → M2.after → M1.after
```

Built via `foldRight`:

```kotlin
val chain = middlewares.foldRight(handlerCall) { mw, acc ->
    { mw.handle(command, acc) }
}
chain()
```

---

## 5. Buses

### 5.1 CommandBus

```kotlin
// CommandBus.kt
class CommandBus(
    private val handlers: Map<KClass<*>, CommandHandler<*, *>>,
    private val middlewares: List<CommandMiddleware>,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <R> dispatch(command: Command<R>): CommandResponse<R> {
        val handler = handlers[command::class]
            ?: error("No handler registered for ${command::class.simpleName}")
        val typedHandler = handler as CommandHandler<Command<R>, R>

        val handlerCall: suspend () -> CommandResponse<R> = { typedHandler.handle(command) }

        val chain = middlewares.foldRight(handlerCall) { mw, next ->
            { mw.handle(command, next) }
        }
        return chain()
    }
}
```

The `@Suppress("UNCHECKED_CAST")` is safe because the DSL builder pairs `KClass<C>` with `CommandHandler<C, R>` at registration time using `reified` type parameters.

### 5.2 QueryBus

```kotlin
// QueryBus.kt
class QueryBus(
    private val handlers: Map<KClass<*>, QueryHandler<*, *>>,
    private val middlewares: List<QueryMiddleware>,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <R> dispatch(query: Query<R>): R {
        val handler = handlers[query::class]
            ?: error("No handler registered for ${query::class.simpleName}")
        val typedHandler = handler as QueryHandler<Query<R>, R>

        val handlerCall: suspend () -> R = { typedHandler.handle(query) }

        val chain = middlewares.foldRight(handlerCall) { mw, next ->
            { mw.handle(query, next) }
        }
        return chain()
    }
}
```

---

## 6. EventDispatcherMiddleware

A built-in `CommandMiddleware` that dispatches events from `CommandResponse` to registered listeners **after** handler execution completes.

```kotlin
// EventDispatcherMiddleware.kt
class EventDispatcherMiddleware(
    private val listeners: List<suspend (Any) -> Unit>,
) : CommandMiddleware {

    override suspend fun <R> handle(
        command: Command<R>,
        next: suspend () -> CommandResponse<R>,
    ): CommandResponse<R> {
        val response = next()
        for (event in response.events) {
            for (listener in listeners) {
                listener(event)
            }
        }
        return response
    }
}
```

### Type-filtered listeners

The DSL provides an `on<T>` helper with `reified` type filtering:

```kotlin
inline fun <reified T> EventListenerBuilder.on(noinline handler: suspend (T) -> Unit) {
    listener { event -> if (event is T) handler(event) }
}
```

Host app usage:

```kotlin
val eventMiddleware = eventDispatcherMiddleware {
    on<DomainEvent.InvoicePaid> { event -> checkVatThreshold.execute(event) }
    on<DomainEvent.InvoiceOverdue> { event -> notifyOverdue(event) }
}
```

---

## 7. DSL Builders

### 7.1 CommandBus Builder

```kotlin
// CqrsDsl.kt
fun commandBus(block: CommandBusBuilder.() -> Unit): CommandBus {
    val builder = CommandBusBuilder()
    builder.block()
    return builder.build()
}

class CommandBusBuilder {
    private val handlers = mutableMapOf<KClass<*>, CommandHandler<*, *>>()
    private val middlewares = mutableListOf<CommandMiddleware>()

    inline fun <reified C : Command<R>, R> handle(handler: CommandHandler<C, R>) {
        handlers[C::class] = handler
    }

    fun middleware(mw: CommandMiddleware) {
        middlewares.add(mw)
    }

    fun build(): CommandBus = CommandBus(handlers.toMap(), middlewares.toList())
}
```

### 7.2 QueryBus Builder

```kotlin
fun queryBus(block: QueryBusBuilder.() -> Unit): QueryBus {
    val builder = QueryBusBuilder()
    builder.block()
    return builder.build()
}

class QueryBusBuilder {
    private val handlers = mutableMapOf<KClass<*>, QueryHandler<*, *>>()
    private val middlewares = mutableListOf<QueryMiddleware>()

    inline fun <reified Q : Query<R>, R> handle(handler: QueryHandler<Q, R>) {
        handlers[Q::class] = handler
    }

    fun middleware(mw: QueryMiddleware) {
        middlewares.add(mw)
    }

    fun build(): QueryBus = QueryBus(handlers.toMap(), middlewares.toList())
}
```

### 7.3 EventDispatcherMiddleware Builder

```kotlin
fun eventDispatcherMiddleware(block: EventListenerBuilder.() -> Unit): EventDispatcherMiddleware {
    val builder = EventListenerBuilder()
    builder.block()
    return builder.build()
}

class EventListenerBuilder {
    private val listeners = mutableListOf<suspend (Any) -> Unit>()

    fun listener(handler: suspend (Any) -> Unit) {
        listeners.add(handler)
    }

    inline fun <reified T> on(noinline handler: suspend (T) -> Unit) {
        listener { event -> if (event is T) handler(event) }
    }

    fun build(): EventDispatcherMiddleware = EventDispatcherMiddleware(listeners.toList())
}
```

---

## 8. Gradle Configuration

### 8.1 `settings.gradle.kts`

```kotlin
rootProject.name = "mona"
include("cqrs")
```

### 8.2 `cqrs/build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}
```

Kotlin plugin version and JVM toolchain are inherited from the root project via `subprojects` block.

### 8.3 Root `build.gradle.kts` changes

Add `allprojects` / `subprojects` blocks to share common config:

```kotlin
allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
```

Add dependency in root module:

```kotlin
dependencies {
    implementation(project(":cqrs"))
    // ... existing deps
}
```

Move `kotlin { jvmToolchain(21) }` to a `subprojects` block so the `cqrs` module inherits it.

---

## 9. Migration Path (Phase 2 — Future)

Not in scope for the initial implementation but documented here for reference.

### 9.1 Use Case Classification

**Commands** (state-changing, return events):
- CreateInvoice, SendInvoice, MarkInvoicePaid, UpdateDraft, DeleteDraft
- CancelInvoice, CorrectInvoice, UpdateClient, SetupProfile
- FinalizeInvoice, ConfigureSetting, DeleteAccount, HandleBouncedEmail, CheckVatThreshold

**Queries** (read-only, no events):
- GetRevenue, GetUnpaidInvoices, ExportInvoicesCsv, ListClients, GetClientHistory, ExportGdprData

**Jobs** (stay outside bus initially):
- OverdueTransitionJob, PaymentCheckInJob, UrssafReminderJob, OnboardingRecoveryJob
- Can dispatch commands through the bus in a later phase

### 9.2 Migration Steps per Use Case

1. Add `Command<R>` marker to the existing command data class (e.g., `data class SendInvoiceCommand(...) : Command<DomainResult<SendInvoiceResult>>`)
2. Make the use case class implement `CommandHandler<XxxCommand, DomainResult<XxxResult>>`
3. Rename `execute` to `handle`, change return type to `CommandResponse<DomainResult<XxxResult>>`
4. Replace `eventDispatcher.dispatch(events)` with returning `CommandResponse(result, events)`
5. Remove `EventDispatcher` constructor parameter

### 9.3 App.kt Wiring (After Migration)

```kotlin
val eventMiddleware = eventDispatcherMiddleware {
    on<DomainEvent.InvoicePaid> { checkVatThreshold.execute(it) }
    on<DomainEvent.InvoiceOverdue> { notifyOverdue(it) }
}

val cmdBus = commandBus {
    middleware(eventMiddleware)

    handle<SendInvoiceCommand, DomainResult<SendInvoiceResult>>(sendInvoice)
    handle<CreateInvoiceCommand, DomainResult<CreateInvoiceResult>>(createInvoice)
    handle<MarkInvoicePaidCommand, DomainResult<Invoice>>(markInvoicePaid)
    // ... all command handlers
}

val qryBus = queryBus {
    handle<GetRevenueQuery, GetRevenueResult>(getRevenue)
    handle<GetUnpaidInvoicesQuery, GetUnpaidInvoicesResult>(getUnpaid)
    // ... all query handlers
}
```

### 9.4 MessageRouter Changes

Replace direct use case references with bus dispatch:

```kotlin
// Before
sendInvoice.execute(command)

// After
commandBus.dispatch(command).result
```

### 9.5 Cleanup

Delete `mona.application.EventDispatcher` and its test once all use cases are migrated.

---

## 10. Verification Checklist

### Phase 1 (framework module)
- [ ] `./gradlew :cqrs:build` compiles with zero warnings
- [ ] `./gradlew :cqrs:test` passes — CommandBus, QueryBus, EventDispatcherMiddleware, DSL
- [ ] `./gradlew build` — full project compiles (no behavior change)
- [ ] `./gradlew ktlintCheck` — passes for both modules
- [ ] Framework has zero imports from `mona.*`

### Phase 2 (migration — future)
- [ ] All command use cases implement `CommandHandler`
- [ ] All query use cases implement `QueryHandler`
- [ ] No use case directly references `EventDispatcher`
- [ ] `MessageRouter` dispatches through buses
- [ ] `EventDispatcher.kt` deleted
- [ ] All existing tests pass
- [ ] Golden tests pass
