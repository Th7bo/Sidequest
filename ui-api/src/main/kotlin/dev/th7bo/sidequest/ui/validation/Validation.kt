package dev.th7bo.sidequest.ui.validation

import dev.th7bo.sidequest.ui.ids.UiId

/** How badly a value fails. Only [ERROR] blocks persistence. */
public enum class Severity {
    /** Worth telling the user, but the value is usable and will be saved. */
    WARNING,

    /** The value is not usable. It must not reach the persisted configuration. */
    ERROR,
}

/**
 * One problem with one value.
 *
 * Carries everything needed to render a useful message next to the offending control
 * and to navigate to whatever else is involved — a bare boolean "invalid" is not enough
 * to build a UI on.
 */
public data class ValidationIssue(
    public val severity: Severity,
    /** Shown to the user. Complete sentence, no trailing period needed. */
    public val message: String,
    /** The setting the issue belongs to. */
    public val field: UiId,
    /** Optional concrete suggestion, e.g. "Use a value between 1 and 60". */
    public val remediation: String? = null,
    /** Other settings involved, for cross-field rules. */
    public val relatedFields: List<UiId> = emptyList(),
)

/**
 * The outcome of validating one value.
 *
 * A result with only warnings is still [isValid]: warnings inform, errors block.
 */
public class ValidationResult private constructor(
    public val issues: List<ValidationIssue>,
) {

    public val isValid: Boolean get() = issues.none { it.severity == Severity.ERROR }

    public val hasWarnings: Boolean get() = issues.any { it.severity == Severity.WARNING }

    public val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.ERROR }

    public val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }

    /** The first error, or the first warning if there are no errors. */
    public val primaryIssue: ValidationIssue?
        get() = errors.firstOrNull() ?: warnings.firstOrNull()

    public operator fun plus(other: ValidationResult): ValidationResult =
        if (issues.isEmpty()) other
        else if (other.issues.isEmpty()) this
        else ValidationResult(issues + other.issues)

    override fun toString(): String =
        if (issues.isEmpty()) "valid" else issues.joinToString("; ") { "${it.severity}: ${it.message}" }

    public companion object {
        private val VALID = ValidationResult(emptyList())

        public fun valid(): ValidationResult = VALID

        public fun of(issues: List<ValidationIssue>): ValidationResult =
            if (issues.isEmpty()) VALID else ValidationResult(issues)

        public fun error(
            field: UiId,
            message: String,
            remediation: String? = null,
            relatedFields: List<UiId> = emptyList(),
        ): ValidationResult = ValidationResult(
            listOf(ValidationIssue(Severity.ERROR, message, field, remediation, relatedFields)),
        )

        public fun warning(
            field: UiId,
            message: String,
            remediation: String? = null,
            relatedFields: List<UiId> = emptyList(),
        ): ValidationResult = ValidationResult(
            listOf(ValidationIssue(Severity.WARNING, message, field, remediation, relatedFields)),
        )
    }
}

/**
 * Checks one typed value.
 *
 * Validators are pure and synchronous. They receive the field's id so that the issues
 * they produce can be attributed without the caller having to rewrite them.
 */
public fun interface Validator<T> {

    public fun validate(field: UiId, value: T): ValidationResult

    /** Runs both, concatenating their issues. */
    public infix fun and(other: Validator<T>): Validator<T> = Validator { field, value ->
        validate(field, value) + other.validate(field, value)
    }
}

/**
 * Validation that has to consult something slow — a network call, a file check.
 *
 * Kept separate from [Validator] so that the synchronous path stays synchronous. The
 * runtime shows the last known result while an async check is in flight, and never
 * blocks a frame on one.
 */
public fun interface AsyncValidator<T> {

    /** Invoked off the UI thread. The result is delivered back through the scheduler. */
    public suspend fun validate(field: UiId, value: T): ValidationResult
}

/** Built-in validators. Compose them with [Validator.and]. */
public object Validators {

    /** Rejects null. */
    public fun <T : Any> required(message: String = "This value is required"): Validator<T?> =
        Validator { field, value ->
            if (value == null) ValidationResult.error(field, message) else ValidationResult.valid()
        }

    /** Rejects blank strings, and by extension null-ish empty input. */
    public fun notBlank(message: String = "This value cannot be empty"): Validator<String> =
        Validator { field, value ->
            if (value.isBlank()) ValidationResult.error(field, message) else ValidationResult.valid()
        }

    public fun intRange(
        range: IntRange,
        severity: Severity = Severity.ERROR,
    ): Validator<Int> = Validator { field, value ->
        if (value in range) {
            ValidationResult.valid()
        } else {
            issue(
                severity,
                field,
                "Must be between ${range.first} and ${range.last}",
                "Use a value from ${range.first} to ${range.last}",
            )
        }
    }

    public fun floatRange(
        range: ClosedFloatingPointRange<Float>,
        severity: Severity = Severity.ERROR,
    ): Validator<Float> = Validator { field, value ->
        if (value in range) {
            ValidationResult.valid()
        } else {
            issue(
                severity,
                field,
                "Must be between ${range.start} and ${range.endInclusive}",
                "Use a value from ${range.start} to ${range.endInclusive}",
            )
        }
    }

    public fun length(
        range: IntRange,
        severity: Severity = Severity.ERROR,
    ): Validator<String> = Validator { field, value ->
        if (value.length in range) {
            ValidationResult.valid()
        } else {
            issue(
                severity,
                field,
                "Must be ${range.first} to ${range.last} characters, but was ${value.length}",
            )
        }
    }

    /** The value must match [pattern] in full. */
    public fun matches(
        pattern: Regex,
        message: String = "Does not match the required format",
        severity: Severity = Severity.ERROR,
    ): Validator<String> = Validator { field, value ->
        if (pattern.matches(value)) ValidationResult.valid() else issue(severity, field, message)
    }

    /**
     * The value must itself be a valid regular expression.
     *
     * The compilation error is included, because "invalid regex" without saying why is
     * not something a user can act on.
     */
    public fun validRegex(): Validator<String> = Validator { field, value ->
        try {
            Regex(value)
            ValidationResult.valid()
        } catch (failure: IllegalArgumentException) {
            ValidationResult.error(
                field,
                "Not a valid regular expression: ${failure.message ?: "unparseable"}",
            )
        }
    }

    /** The value must be one of [allowed]. */
    public fun <T> oneOf(allowed: Collection<T>): Validator<T> = Validator { field, value ->
        if (value in allowed) {
            ValidationResult.valid()
        } else {
            ValidationResult.error(field, "'$value' is not one of the allowed values")
        }
    }

    /** Arbitrary predicate. Fails with [message] when [predicate] returns false. */
    public fun <T> satisfies(
        message: String,
        severity: Severity = Severity.ERROR,
        remediation: String? = null,
        predicate: (T) -> Boolean,
    ): Validator<T> = Validator { field, value ->
        if (predicate(value)) ValidationResult.valid() else issue(severity, field, message, remediation)
    }

    /**
     * A rule that depends on another setting's current value.
     *
     * The other field is recorded in [ValidationIssue.relatedFields], so the UI can
     * offer to jump to it rather than leaving the user to find what conflicts.
     */
    public fun <T, O> crossField(
        other: UiId,
        otherValue: () -> O,
        message: String,
        severity: Severity = Severity.ERROR,
        remediation: String? = null,
        predicate: (T, O) -> Boolean,
    ): Validator<T> = Validator { field, value ->
        if (predicate(value, otherValue())) {
            ValidationResult.valid()
        } else {
            ValidationResult.of(
                listOf(ValidationIssue(severity, message, field, remediation, listOf(other))),
            )
        }
    }

    private fun issue(
        severity: Severity,
        field: UiId,
        message: String,
        remediation: String? = null,
    ): ValidationResult = when (severity) {
        Severity.ERROR -> ValidationResult.error(field, message, remediation)
        Severity.WARNING -> ValidationResult.warning(field, message, remediation)
    }
}
