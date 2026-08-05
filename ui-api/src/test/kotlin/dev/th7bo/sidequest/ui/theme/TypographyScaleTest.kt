package dev.th7bo.sidequest.ui.theme

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypographyScaleTest {

    @Test
    fun `default roles stay at one displayed size`() {
        val typography = TypographyScale()
        val roles = listOf(
            typography.title,
            typography.label,
            typography.body,
            typography.secondary,
            typography.caption,
            typography.mono,
        )

        assertTrue(roles.all { it.scale == 1f })
    }

    @Test
    fun `hierarchy uses native bold instead of bitmap rescaling`() {
        val typography = TypographyScale()

        assertTrue(typography.title.bold)
        assertFalse(typography.label.bold)
        assertFalse(typography.secondary.bold)
        assertFalse(typography.caption.bold)
    }
}
