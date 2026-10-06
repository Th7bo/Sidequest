package dev.th7bo.sidequest.platform.core.garden

import dev.th7bo.sidequest.platform.core.garden.GardenTimeTodo.Advice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Night before the pests, Day before killing them. See [GardenTimeTodo]. */
class GardenTimeTodoTest {

    @Test
    fun `the chat lines are read`() {
        assertEquals(GardenTime.DAY, GardenTimeChat.timeSet("Your garden time has been set to Day!"))
        assertEquals(GardenTime.NIGHT, GardenTimeChat.timeSet("Your garden time has been set to Night!"))
        assertEquals("Locust", GardenTimeChat.pestKilled("You received 7x Enchanted Potato for killing a Locust!"))
        assertEquals("Earthworm", GardenTimeChat.pestKilled("You received 2x Enchanted Melon for killing an Earthworm!"))
    }

    @Test
    fun `a quoted line changes nothing`() {
        assertNull(GardenTimeChat.timeSet("Party > someone: Your garden time has been set to Day!"))
        assertNull(GardenTimeChat.pestKilled("Party > someone: You received 7x Enchanted Potato for killing a Locust!"))
        assertNull(GardenTimeChat.timeSet("You farmed some wheat!"))
    }

    @Test
    fun `the routine done right says nothing alarming`() {
        val todo = GardenTimeTodo()
        assertEquals(Advice.None, todo.onTimeSet(GardenTime.NIGHT))
        assertNull(todo.todo)

        assertEquals(Advice.SetDay, todo.onSpawn(2))
        assertEquals(GardenTime.DAY, todo.todo)

        assertEquals(Advice.ReadyToKill, todo.onTimeSet(GardenTime.DAY))
        assertNull(todo.todo)

        assertEquals(Advice.None, todo.onKill())
        assertEquals(Advice.AllClear, todo.onKill())
        assertEquals(GardenTime.NIGHT, todo.todo)
    }

    @Test
    fun `killing a pest at night is the loud one`() {
        val todo = GardenTimeTodo()
        todo.onTimeSet(GardenTime.NIGHT)
        todo.onSpawn(1)
        assertEquals(Advice.KilledAtNight, todo.onKill())
    }

    /** Pests already out before the mod saw them spawn — after a reconnect — still count when they die. */
    @Test
    fun `a kill nobody saw spawn is still a kill`() {
        val todo = GardenTimeTodo()
        todo.onTimeSet(GardenTime.NIGHT)
        assertEquals(Advice.KilledAtNight, todo.onKill())
        assertEquals(0, todo.pestsOut)
    }

    @Test
    fun `a spawn during the day missed the night`() {
        val todo = GardenTimeTodo()
        todo.onTimeSet(GardenTime.DAY)
        assertEquals(Advice.SpawnedInDay, todo.onSpawn(1))
        assertNull(todo.todo)
    }

    @Test
    fun `going back to night with pests out is caught before the kill`() {
        val todo = GardenTimeTodo()
        todo.onTimeSet(GardenTime.DAY)
        todo.onSpawn(1)
        assertEquals(Advice.NightWithPestsOut, todo.onTimeSet(GardenTime.NIGHT))
        assertEquals(GardenTime.DAY, todo.todo)
    }

    @Test
    fun `an unknown time asks for night once the pests are gone`() {
        val todo = GardenTimeTodo()
        assertEquals(GardenTime.NIGHT, todo.todo)
        assertEquals(Advice.None, todo.onKill())
    }
}
