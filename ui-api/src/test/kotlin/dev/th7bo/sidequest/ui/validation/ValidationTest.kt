package dev.th7bo.sidequest.ui.validation

import dev.th7bo.sidequest.ui.ids.UiId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationTest {

    private val field = UiId.of("sidequest", "general.duration")
    private val other = UiId.of("sidequest", "general.maximum")

    @Test
    fun `a result with only warnings is still valid`() {
        val result = ValidationResult.warning(field, "That is unusually high")

        assertTrue(result.isValid, "warnings inform, they do not block")
        assertTrue(result.hasWarnings)
        assertEquals(1, result.warnings.size)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `a result with an error is invalid`() {
        val result = ValidationResult.error(field, "Out of range")

        assertFalse(result.isValid)
        assertEquals(Severity.ERROR, result.primaryIssue?.severity)
    }

    @Test
    fun `errors are reported before warnings`() {
        val combined = ValidationResult.warning(field, "warn") + ValidationResult.error(field, "fail")

        assertEquals("fail", combined.primaryIssue?.message)
    }

    @Test
    fun `combining with a valid result is free`() {
        val error = ValidationResult.error(field, "bad")

        assertEquals(error, ValidationResult.valid() + error)
        assertEquals(error, error + ValidationResult.valid())
    }

    @Test
    fun `int range reports the bounds and how to fix it`() {
        val validator = Validators.intRange(1..60)

        assertTrue(validator.validate(field, 30).isValid)

        val failure = validator.validate(field, 61)
        assertFalse(failure.isValid)
        assertEquals("Must be between 1 and 60", failure.errors.single().message)
        assertNotNull(failure.errors.single().remediation)
        assertEquals(field, failure.errors.single().field)
    }

    @Test
    fun `range severity is configurable so a soft limit can warn instead of block`() {
        val soft = Validators.intRange(1..60, severity = Severity.WARNING)
        val result = soft.validate(field, 500)

        assertTrue(result.isValid)
        assertTrue(result.hasWarnings)
    }

    @Test
    fun `float range accepts the bounds themselves`() {
        val validator = Validators.floatRange(0f..1f)

        assertTrue(validator.validate(field, 0f).isValid)
        assertTrue(validator.validate(field, 1f).isValid)
        assertFalse(validator.validate(field, 1.0001f).isValid)
    }

    @Test
    fun `notBlank rejects whitespace-only input`() {
        val validator = Validators.notBlank()

        assertFalse(validator.validate(field, "   ").isValid)
        assertFalse(validator.validate(field, "").isValid)
        assertTrue(validator.validate(field, "x").isValid)
    }

    @Test
    fun `length reports the actual size`() {
        val result = Validators.length(3..8).validate(field, "ab")

        assertFalse(result.isValid)
        assertTrue(result.errors.single().message.contains("was 2"))
    }

    @Test
    fun `matches requires a full match, not a partial one`() {
        val validator = Validators.matches(Regex("[a-z]+"))

        assertTrue(validator.validate(field, "abc").isValid)
        assertFalse(validator.validate(field, "abc1").isValid, "a trailing digit must fail")
    }

    @Test
    fun `validRegex explains why a pattern failed to compile`() {
        val validator = Validators.validRegex()

        assertTrue(validator.validate(field, "[a-z]+").isValid)

        val failure = validator.validate(field, "[unclosed")
        assertFalse(failure.isValid)
        assertTrue(
            failure.errors.single().message.length > "Not a valid regular expression: ".length,
            "the compilation error must be included, not just 'invalid'",
        )
    }

    @Test
    fun `oneOf rejects values outside the allowed set`() {
        val validator = Validators.oneOf(listOf("dark", "light"))

        assertTrue(validator.validate(field, "dark").isValid)
        assertFalse(validator.validate(field, "solarized").isValid)
    }

    @Test
    fun `cross-field validation records the related field`() {
        var maximum = 10
        val validator = Validators.crossField<Int, Int>(
            other = other,
            otherValue = { maximum },
            message = "Must not exceed the maximum",
            remediation = "Raise the maximum, or lower this value",
        ) { value, max -> value <= max }

        assertTrue(validator.validate(field, 5).isValid)

        val failure = validator.validate(field, 20)
        assertFalse(failure.isValid)
        assertEquals(listOf(other), failure.errors.single().relatedFields)
        assertNotNull(failure.errors.single().remediation)

        // The rule re-reads the other value each time rather than capturing it once.
        maximum = 50
        assertTrue(validator.validate(field, 20).isValid)
    }

    @Test
    fun `validators compose and concatenate their issues`() {
        val combined = Validators.notBlank() and Validators.length(5..10)

        val result = combined.validate(field, "")

        assertEquals(2, result.errors.size, "both rules must report, not just the first")
    }

    @Test
    fun `satisfies carries a custom message and severity`() {
        val validator = Validators.satisfies<Int>(
            message = "Must be even",
            severity = Severity.WARNING,
            remediation = "Round to the nearest even number",
        ) { it % 2 == 0 }

        val result = validator.validate(field, 3)
        assertTrue(result.isValid)
        assertEquals("Must be even", result.warnings.single().message)
    }

    @Test
    fun `required rejects null`() {
        val validator = Validators.required<String>()

        assertFalse(validator.validate(field, null).isValid)
        assertTrue(validator.validate(field, "x").isValid)
    }
}
