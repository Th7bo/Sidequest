package dev.th7bo.sidequest.platform.core.achievement

import dev.th7bo.sidequest.platform.achievement.Achievement
import dev.th7bo.sidequest.platform.achievement.AchievementCategory
import dev.th7bo.sidequest.platform.achievement.AchievementSource
import dev.th7bo.sidequest.platform.achievement.AchievementStore
import dev.th7bo.sidequest.platform.achievement.AchievementUnlockedEvent
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.testkit.EventRecorder
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The achievement service.
 *
 * The assertions that matter are the ones about doing it twice: a second unlock is a normal race between
 * two rules naming one achievement, and the whole point is that losing it is quiet and leaves the first
 * unlock exactly as it was.
 */
class AchievementServiceTest {

    private lateinit var bus: DefaultEventBus
    private lateinit var events: EventRecorder
    private lateinit var service: DefaultAchievementService

    private var clock = 1_000L

    private val me = PlayerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val friend = PlayerId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"))

    private val first = achievement("group.first_blood")
    private val hidden = achievement("secret.wrong_lobby", hidden = true)

    @BeforeEach
    fun setUp() {
        bus = DefaultEventBus(TestScheduler(), NoopLogger)
        events = EventRecorder(bus)
        service = DefaultAchievementService(
            events = bus,
            log = NoopLogger,
            localPlayer = { me },
            now = { clock },
        )
        assertTrue(service.register(first))
        assertTrue(service.register(hidden))
    }

    private fun achievement(
        path: String,
        hidden: Boolean = false,
    ) = Achievement(
        id = SqId.sidequest(path),
        displayName = path.substringAfter('.'),
        category = if (hidden) AchievementCategory.SECRET else AchievementCategory.FRIEND_GROUP,
        isHidden = hidden,
    )

    // -- registration ---------------------------------------------------------

    /** Keeping the first, not throwing: two features declaring one achievement should be a warning, not a crash. */
    @Test
    fun `a duplicate id keeps the first definition`() {
        assertFalse(service.register(achievement("group.first_blood")))
        assertEquals(SqId.sidequest("group.first_blood"), service.definitionOf(SqId.sidequest("group.first_blood"))?.id)
    }

    // -- unlocking ------------------------------------------------------------

    @Test
    fun `unlocking records when, for whom, and says so`() {
        clock = 5_000L

        assertTrue(service.unlock(first.id, AchievementSource.RULE))

        val record = service.recordOf(first.id)
        assertEquals(5_000L, record?.unlockedAtMillis)
        assertTrue(service.isUnlocked(first.id))
        assertEquals(AchievementUnlockedEvent::class, events.single<AchievementUnlockedEvent>()::class)
    }

    @Test
    fun `the event names the achievement and who earned it`() {
        service.unlock(first.id, AchievementSource.MANUAL, subject = friend)

        val event = events.single<AchievementUnlockedEvent>()
        assertEquals(first.id, event.achievementId)
        assertEquals(friend, event.subject)
        assertEquals(AchievementSource.MANUAL, event.source)
    }

    /** Two rules naming one achievement both fire. The second loses quietly — that is the design. */
    @Test
    fun `a second unlock changes nothing`() {
        assertTrue(service.unlock(first.id))
        clock += 100
        assertFalse(service.unlock(first.id))

        assertEquals(1_000L, service.recordOf(first.id)?.unlockedAtMillis)
        // Exactly one announcement — the first unlock's. The second said nothing.
        assertEquals(1, events.count<AchievementUnlockedEvent>())
    }

    @Test
    fun `an unknown achievement is refused rather than recorded`() {
        assertFalse(service.unlock(SqId.sidequest("group.never_declared")))
        assertNull(service.recordOf(SqId.sidequest("group.never_declared")))
        events.assertNone<AchievementUnlockedEvent>()
    }

    // -- subjects -------------------------------------------------------------

    /**
     * A null subject means the local player, the same as everywhere else on this platform. Filing a null
     * under a "nobody" key would put one player's cabinet on another's shelf.
     */
    @Test
    fun `a null subject resolves to the local player`() {
        service.unlock(first.id)

        assertTrue(service.isUnlocked(first.id, subject = me))
        assertFalse(service.isUnlocked(first.id, subject = friend))
        assertEquals(me, events.single<AchievementUnlockedEvent>().subject)
    }

    @Test
    fun `two players earn things separately`() {
        service.unlock(first.id, subject = friend)
        service.unlock(hidden.id, subject = me)

        assertEquals(listOf(first.id), service.unlocked(friend).map { it.id })
        assertEquals(listOf(hidden.id), service.unlocked().map { it.id })
    }

    // -- listing --------------------------------------------------------------

    @Test
    fun `hidden achievements stay out until they are earned`() {
        // Unearned: only the visible one, with or without asking.
        assertEquals(listOf(first.id), service.all(includeHidden = false).map { it.id })
        assertEquals(2, service.all(includeHidden = true).size)

        // Earned: in the cabinet like any other.
        service.unlock(hidden.id)
        assertEquals(2, service.all(includeHidden = false).size)
    }

    @Test
    fun `unlocked reads newest first`() {
        clock = 1_000L
        service.unlock(first.id)
        clock = 2_000L
        service.unlock(hidden.id)

        assertEquals(listOf(hidden.id, first.id), service.unlocked().map { it.id })
    }

    // -- persistence ----------------------------------------------------------

    @Test
    fun `every change hands the caller the whole store`() {
        var saved: AchievementStore? = null
        service.onStoreChanged = { saved = it }

        service.unlock(first.id)

        assertEquals(setOf(first.id.value), saved?.byAchievement?.keys)
        assertEquals(1, saved?.byAchievement?.get(first.id.value)?.size)
    }

    @Test
    fun `a round trip through the store keeps the unlocks`() {
        service.unlock(first.id, subject = friend)
        service.unlock(hidden.id)
        val stored = AchievementStore(byAchievement = buildMap {
            for (id in listOf(first.id, hidden.id)) {
                val perSubject = mutableMapOf<String, dev.th7bo.sidequest.platform.achievement.AchievementRecord>()
                for (subject in listOf(me, friend)) {
                    service.recordOf(id, subject)?.let { perSubject[subject.value] = it }
                }
                put(id.value, perSubject)
            }
        })

        val fresh = DefaultAchievementService(bus, NoopLogger, localPlayer = { me }, now = { clock })
        fresh.register(first)
        fresh.register(hidden)
        fresh.load(stored)

        assertTrue(fresh.isUnlocked(first.id, friend))
        assertFalse(fresh.isUnlocked(first.id, me))
        assertTrue(fresh.isUnlocked(hidden.id, me))
    }
}
