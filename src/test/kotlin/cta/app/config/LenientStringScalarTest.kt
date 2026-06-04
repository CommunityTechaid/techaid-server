package cta.app.config

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.IntValue
import graphql.schema.CoercingParseValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.Locale

/**
 * Unit-tests the lenient String coercing in isolation (no Spring context / Docker). Proves that a
 * numeric value — the bulk-insert failure mode, e.g. a numeric lotId — is now stringified rather
 * than rejected, while structural values are still refused.
 */
class LenientStringScalarTest {
    private val coercing = GraphQLScalarConfig().lenientStringScalar().coercing
    private val ctx: GraphQLContext = GraphQLContext.getDefault()
    private val locale: Locale = Locale.getDefault()

    @Test
    fun `parseValue stringifies an Integer instead of rejecting it`() {
        assertEquals("26060301", coercing.parseValue(26060301, ctx, locale))
    }

    @Test
    fun `parseValue passes a String through unchanged`() {
        assertEquals("Latitude", coercing.parseValue("Latitude", ctx, locale))
    }

    @Test
    fun `parseValue stringifies a Boolean`() {
        assertEquals("true", coercing.parseValue(true, ctx, locale))
    }

    @Test
    fun `parseValue still rejects a structural (list) value`() {
        assertThrows(CoercingParseValueException::class.java) {
            coercing.parseValue(listOf(1, 2), ctx, locale)
        }
    }

    @Test
    fun `parseLiteral stringifies an IntValue literal`() {
        val literal = IntValue.newIntValue(BigInteger.valueOf(123)).build()
        assertEquals("123", coercing.parseLiteral(literal, CoercedVariables.emptyVariables(), ctx, locale))
    }
}
