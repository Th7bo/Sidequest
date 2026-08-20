package dev.th7bo.sidequest.platform.core.garden

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
     * The gap after the article is two spaces, not one.
     *
     * Hypixel puts the pest's icon there and cleaning strips it. A pattern written with one space — which is
     * what the line looks like — matches nothing, forever, and nothing looks broken.
     */
    @Test
    fun `a single space is not the message`() {
        assertNull(PestChat.spawn("GROSS! A Pest has appeared in Plot - S 4!"))
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
