package mona.domain.model

sealed class DomainResult<out T> {
    data class Ok<T>(val value: T) : DomainResult<T>()

    data class Err(val error: DomainError) : DomainResult<Nothing>()
}

inline fun <T, R> DomainResult<T>.map(f: (T) -> R): DomainResult<R> =
    when (this) {
        is DomainResult.Ok -> DomainResult.Ok(f(value))
        is DomainResult.Err -> this
    }

inline fun <T, R> DomainResult<T>.flatMap(f: (T) -> DomainResult<R>): DomainResult<R> =
    when (this) {
        is DomainResult.Ok -> f(value)
        is DomainResult.Err -> this
    }
