package mona.domain

import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.flatMap
import mona.domain.model.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DomainResultTest {
    @Test
    fun `map on Ok propagates value`() {
        val result: DomainResult<Int> = DomainResult.Ok(5)
        val mapped = result.map { it * 2 }
        assertIs<DomainResult.Ok<Int>>(mapped)
        assertEquals(10, mapped.value)
    }

    @Test
    fun `map on Err short-circuits`() {
        val error = DomainError.EmptyLineItems
        val result: DomainResult<Int> = DomainResult.Err(error)
        val mapped = result.map { it * 2 }
        assertIs<DomainResult.Err>(mapped)
        assertEquals(error, mapped.error)
    }

    @Test
    fun `flatMap chains correctly`() {
        val result: DomainResult<Int> = DomainResult.Ok(5)
        val chained = result.flatMap { DomainResult.Ok(it.toString()) }
        assertIs<DomainResult.Ok<String>>(chained)
        assertEquals("5", chained.value)
    }

    @Test
    fun `flatMap short-circuits on Err`() {
        val error = DomainError.EmptyLineItems
        val result: DomainResult<Int> = DomainResult.Err(error)
        var called = false
        val chained =
            result.flatMap {
                called = true
                DomainResult.Ok(it.toString())
            }
        assertIs<DomainResult.Err>(chained)
        assertEquals(false, called)
    }

    @Test
    fun `flatMap propagates error from inner function`() {
        val result: DomainResult<Int> = DomainResult.Ok(5)
        val error = DomainError.NegativeAmount(-100)
        val chained = result.flatMap { DomainResult.Err(error) }
        assertIs<DomainResult.Err>(chained)
        assertEquals(error, chained.error)
    }
}
