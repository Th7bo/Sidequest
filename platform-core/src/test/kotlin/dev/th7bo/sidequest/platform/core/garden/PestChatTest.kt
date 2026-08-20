package dev.th7bo.sidequest.platform.core.garden

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The line that says a pest arrived.
 *
 * Two details decide whether this works at all, and neither is visible to somebody reading the message in
 * game — see [PestChat]. Both carry a test that fails without them.
 */
class PestChatTest {

    @Test
    fun `one pest and several are both announced`() {
        assertEquals(1, PestChat.spawn("GROSS! A  Pest has appeared in Plot - S 4!")?.amount)
        assertEquals(1, PestChat.spawn("GROSS! A  Pest has appeared in The Barn!")?.amount)
        assertEquals(4, PestChat.spawn("YUCK! 4  Pest have spawned in Plot - 14!")?.amount)
        assertEquals(4, PestChat.spawn("YUCK! 4  Pest have spawned in The Barn!")?.amount)
    }

    @Test
    fun `the plot is read out of the announcement`() {
        assertEquals("S 4", PestChat.spawn("GROSS! A  Pest has appeared in Plot - S 4!")?.plot)
        assertEquals("The Barn", PestChat.spawn("GROSS! A  Pest has appeared in The Barn!")?.plot)
    }

    /**
     * However the line has been cleaned, it still reads as a spawn.
     *
     * What sits between the article and the word is Hypixel's pest icon, a private-use glyph. This mod's
     * cleaning keeps such glyphs and collapses runs of spaces; another mod's strips them and does not. So
     * the same line arrives as any of three shapes depending on who cleaned it, and a pattern pinned to one
     * of them matches nothing while nothing looks broken. That is exactly what happened.
     */
    @Test
    fun `the icon between the words may survive cleaning or not`() {
        val forms = listOf(
            "YUCK! 6 \uE123 Pest have spawned in Plot - 3!",
            "YUCK! 6  Pest have spawned in Plot - 3!",
            "YUCK! 6 Pest have spawned in Plot - 3!",
        )
        for (form in forms) {
            val spawn = PestChat.spawn(form)
            assertEquals(6, spawn?.amount, "did not read: $form")
            assertEquals("3", spawn?.plot)
        }

        for (form in listOf(
            "GROSS! A \uE123 Pest has appeared in Plot - S 4!",
            "GROSS! A  Pest has appeared in Plot - S 4!",
            "GROSS! A Pest has appeared in Plot - S 4!",
        )) {
            assertEquals(1, PestChat.spawn(form)?.amount, "did not read: $form")
        }
    }

    /**
     * Somebody quoting it in chat is not a pest.
     *
     * Without the anchor, one party member saying "GROSS! A  Pest has appeared" would stop the camera for
     * everybody who could see it.
     */
    @Test
    fun `a quoted message is not a spawn`() {
        assertFalse(PestChat.isSpawn("From [MVP+] ThePleader: GROSS! A  Pest has appeared in Plot - 67!"))
        assertFalse(PestChat.isSpawn("Party > chrooted: YUCK! 4  Pest have spawned in Plot - 14!"))
    }

    @Test
    fun `ordinary chat is ignored`() {
        assertFalse(PestChat.isSpawn("You farmed some wheat!"))
        assertFalse(PestChat.isSpawn(""))
    }
}
