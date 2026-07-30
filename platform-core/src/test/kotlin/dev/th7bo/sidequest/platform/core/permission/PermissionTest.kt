package dev.th7bo.sidequest.platform.core.permission

import dev.th7bo.sidequest.platform.permission.GroupRole
import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.permission.PermissionKind
import dev.th7bo.sidequest.platform.permission.PermissionSettings
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Permissions and privacy.
 *
 * The tests that matter are the ones about the capability/disclosure split. The plan lists both kinds
 * in one flat list, and a service that treats them as one produces a privacy gate that is decorative:
 * an admin would see a member's exact position because admins can do things, which is not what
 * agreeing to share your island agreed to.
 */
class PermissionTest {

    private lateinit var permissions: DefaultPermissionService

    private val me = PlayerId("00000000-0000-4000-8000-000000000000")
    private val owner = PlayerId("11111111-1111-4111-8111-111111111111")
    private val admin = PlayerId("22222222-2222-4222-8222-222222222222")
    private val member = PlayerId("33333333-3333-4333-8333-333333333333")
    private val stranger = PlayerId("44444444-4444-4444-8444-444444444444")

    @BeforeEach
    fun setUp() {
        permissions = DefaultPermissionService(NoopLogger, localPlayer = { me })
        permissions.update(
            PermissionSettings(
                roles = mapOf(
                    owner.value to GroupRole.OWNER,
                    admin.value to GroupRole.ADMIN,
                    member.value to GroupRole.MEMBER,
                ),
            ),
        )
    }

    // ---------------------------------------------------------------
    // Roles
    // ---------------------------------------------------------------

    /** Somebody the group has not decided about gets the least, not the default. */
    @Test
    fun `an unknown player is a guest`() {
        assertEquals(GroupRole.GUEST, permissions.roleOf(stranger))
    }

    @Test
    fun `roles are ordered so a check can read as admin or better`() {
        assertTrue(GroupRole.OWNER.isAtLeast(GroupRole.ADMIN))
        assertTrue(GroupRole.ADMIN.isAtLeast(GroupRole.ADMIN))
        assertFalse(GroupRole.MEMBER.isAtLeast(GroupRole.ADMIN))
        assertFalse(GroupRole.GUEST.isAtLeast(GroupRole.MEMBER))
    }

    // ---------------------------------------------------------------
    // Capabilities
    // ---------------------------------------------------------------

    @Test
    fun `an ordinary capability is a member's`() {
        assertTrue(permissions.can(member, Permission.SEND_PINGS))
        assertTrue(permissions.can(member, Permission.CREATE_READY_CHECKS))
        assertTrue(permissions.can(member, Permission.UPLOAD_EVIDENCE))
    }

    /** A guest can do almost nothing, because a guest is somebody nobody has vouched for. */
    @Test
    fun `a guest cannot do the ordinary things`() {
        assertFalse(permissions.can(stranger, Permission.SEND_PINGS))
        assertFalse(permissions.can(stranger, Permission.CREATE_DEBTS))
        assertFalse(permissions.can(stranger, Permission.UPLOAD_EVIDENCE))
    }

    /**
     * Confirming a payment settles a debt, which is the half that costs somebody something.
     *
     * A member confirming their own payment would make the ledger a formality.
     */
    @Test
    fun `settling and rewriting need an admin`() {
        assertFalse(permissions.can(member, Permission.CONFIRM_PAYMENTS))
        assertFalse(permissions.can(member, Permission.EDIT_EVIDENCE))
        assertFalse(permissions.can(member, Permission.MODERATE_CONTENT))

        assertTrue(permissions.can(admin, Permission.CONFIRM_PAYMENTS))
        assertTrue(permissions.can(admin, Permission.EDIT_EVIDENCE))
        assertTrue(permissions.can(admin, Permission.MODERATE_CONTENT))
    }

    /** Rewards are currency. */
    @Test
    fun `only the owner manages shop rewards`() {
        assertFalse(permissions.can(admin, Permission.MANAGE_SHOP_REWARDS))
        assertTrue(permissions.can(owner, Permission.MANAGE_SHOP_REWARDS))
    }

    @Test
    fun `an owner has everything an admin has`() {
        val adminCan = permissions.capabilitiesOf(admin)
        val ownerCan = permissions.capabilitiesOf(owner)
        assertTrue(ownerCan.containsAll(adminCan))
    }

    // ---------------------------------------------------------------
    // Per-user overrides
    // ---------------------------------------------------------------

    /** "Let them run ready checks" — a member granted something above their role. */
    @Test
    fun `an override can grant above the role`() {
        permissions.setOverride(member, Permission.CONFIRM_PAYMENTS, allowed = true)
        assertTrue(permissions.can(member, Permission.CONFIRM_PAYMENTS))
    }

    /** "That person cannot be trusted with the soundboard" — an admin denied something below it. */
    @Test
    fun `an override can deny below the role`() {
        permissions.setOverride(admin, Permission.TRIGGER_SOUNDS, allowed = false)
        assertFalse(permissions.can(admin, Permission.TRIGGER_SOUNDS))
        assertTrue(permissions.can(member, Permission.TRIGGER_SOUNDS), "nobody else is affected")
    }

    @Test
    fun `clearing an override falls back to the role`() {
        permissions.setOverride(member, Permission.CONFIRM_PAYMENTS, allowed = true)
        permissions.setOverride(member, Permission.CONFIRM_PAYMENTS, allowed = null)
        assertFalse(permissions.can(member, Permission.CONFIRM_PAYMENTS))
    }

    @Test
    fun `clearing the last override removes the entry entirely`() {
        permissions.setOverride(member, Permission.CONFIRM_PAYMENTS, allowed = true)
        permissions.setOverride(member, Permission.CONFIRM_PAYMENTS, allowed = null)
        assertFalse(member.value in permissions.settings.overrides)
    }

    @Test
    fun `a role change does not clear an override`() {
        permissions.setOverride(member, Permission.MANAGE_SHOP_REWARDS, allowed = true)
        permissions.setRole(member, GroupRole.GUEST)
        assertTrue(permissions.can(member, Permission.MANAGE_SHOP_REWARDS))
    }

    @Test
    fun `leaving the group forgets both the role and the overrides`() {
        permissions.setOverride(member, Permission.CONFIRM_PAYMENTS, allowed = true)
        permissions.forget(member)

        assertEquals(GroupRole.GUEST, permissions.roleOf(member))
        assertFalse(permissions.can(member, Permission.CONFIRM_PAYMENTS))
    }

    // ---------------------------------------------------------------
    // Disclosures — the half a role model gets wrong
    // ---------------------------------------------------------------

    /**
     * The distinction that makes the gate real.
     *
     * An admin can do more than a member; that has nothing to do with what a member has agreed to
     * reveal. Position is off by default, and being an owner does not change it.
     */
    @Test
    fun `no role grants a disclosure`() {
        assertFalse(permissions.shares(Permission.VIEW_EXACT_POSITION, owner))
        assertFalse(permissions.shares(Permission.VIEW_EXACT_POSITION, admin))
        assertFalse(permissions.shares(Permission.VIEW_EXACT_POSITION, member))
    }

    /**
     * Exact position is the one thing that lets somebody find a person rather than know about them.
     *
     * A privacy default that has to be turned off is not a privacy default.
     */
    @Test
    fun `exact position is off until it is turned on`() {
        assertFalse(permissions.sharesWithAnybody(Permission.VIEW_EXACT_POSITION))

        permissions.setSharedWithEveryone(Permission.VIEW_EXACT_POSITION, shared = true)
        assertTrue(permissions.shares(Permission.VIEW_EXACT_POSITION, member))
    }

    @Test
    fun `the ordinary disclosures are on by default`() {
        assertTrue(permissions.shares(Permission.VIEW_ONLINE_STATUS, member))
        assertTrue(permissions.shares(Permission.VIEW_ACTIVITY, member))
        assertTrue(permissions.shares(Permission.VIEW_ISLAND, member))
    }

    @Test
    fun `a disclosure can be shared with named people only`() {
        permissions.setDisclosure(Permission.VIEW_EXACT_POSITION, setOf(admin.value))

        assertTrue(permissions.shares(Permission.VIEW_EXACT_POSITION, admin))
        assertFalse(permissions.shares(Permission.VIEW_EXACT_POSITION, member))
        assertFalse(permissions.shares(Permission.VIEW_EXACT_POSITION, owner))
    }

    /**
     * Explicitly off stays off.
     *
     * An empty audience is different from an absent entry: somebody who turned a disclosure off must
     * stay off if the default ever changes.
     */
    @Test
    fun `turning a default-on disclosure off keeps it off`() {
        permissions.setSharedWithEveryone(Permission.VIEW_ONLINE_STATUS, shared = false)

        assertFalse(permissions.shares(Permission.VIEW_ONLINE_STATUS, member))
        assertFalse(permissions.sharesWithAnybody(Permission.VIEW_ONLINE_STATUS))
    }

    /**
     * We can always see ourselves.
     *
     * Without this, the local HUD showing our own position would be gated on us having agreed to
     * share it with somebody.
     */
    @Test
    fun `a disclosure is always shared with ourselves`() {
        assertTrue(permissions.shares(Permission.VIEW_EXACT_POSITION, me))
        permissions.setSharedWithEveryone(Permission.VIEW_EXACT_POSITION, shared = false)
        assertTrue(permissions.shares(Permission.VIEW_EXACT_POSITION, me))
    }

    // ---------------------------------------------------------------
    // Asking the wrong question
    // ---------------------------------------------------------------

    /**
     * Answered as no, not as a plausible yes.
     *
     * A caller that asks `can()` about a disclosure has a bug, and a helpful answer would hide it —
     * while leaking exactly the thing the split exists to protect.
     */
    @Test
    fun `asking can about a disclosure is refused`() {
        assertFalse(permissions.can(owner, Permission.VIEW_EXACT_POSITION))
        assertFalse(permissions.can(owner, Permission.VIEW_ONLINE_STATUS))
    }

    @Test
    fun `asking shares about a capability is refused`() {
        assertFalse(permissions.shares(Permission.SEND_PINGS, member))
        assertFalse(permissions.sharesWithAnybody(Permission.SEND_PINGS))
    }

    @Test
    fun `editing a disclosure as an override is refused loudly`() {
        val thrown = runCatching {
            permissions.setOverride(member, Permission.VIEW_EXACT_POSITION, allowed = true)
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `editing a capability as a disclosure is refused loudly`() {
        val thrown = runCatching {
            permissions.setDisclosure(Permission.SEND_PINGS, setOf(PermissionSettings.EVERYONE))
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is IllegalArgumentException)
    }

    // ---------------------------------------------------------------
    // The permission catalogue itself
    // ---------------------------------------------------------------

    /** Every permission is one kind or the other, and the two lists partition the enum. */
    @Test
    fun `every permission is exactly one kind`() {
        assertEquals(
            Permission.entries.size,
            Permission.CAPABILITIES.size + Permission.DISCLOSURES.size,
        )
        assertTrue(Permission.CAPABILITIES.none { it in Permission.DISCLOSURES })
    }

    /**
     * Nothing that reveals where a person physically is may default to shared.
     *
     * Asserted over the enum rather than per permission, so a disclosure added later has to make the
     * same decision deliberately instead of inheriting a convenient default.
     */
    @Test
    fun `position-revealing disclosures do not default to shared`() {
        assertFalse(Permission.VIEW_EXACT_POSITION.sharedByDefault)
    }

    @Test
    fun `no capability is available to a guest by default`() {
        val guestCan = Permission.CAPABILITIES.filter { it.defaultRole == GroupRole.GUEST }
        assertEquals(emptyList<Permission>(), guestCan)
    }

    @Test
    fun `every permission has a name worth showing a user`() {
        for (permission in Permission.entries) {
            assertTrue(permission.displayName.isNotBlank()) { "$permission has no display name" }
            assertFalse(permission.displayName == permission.name) { "$permission shows its enum name" }
        }
    }

    // ---------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------

    /** The settings are one value, so they are one repository and one sync payload. */
    @Test
    fun `settings round-trip through JSON`() {
        permissions.setRole(member, GroupRole.ADMIN)
        permissions.setOverride(member, Permission.MANAGE_SHOP_REWARDS, allowed = true)
        permissions.setDisclosure(Permission.VIEW_EXACT_POSITION, setOf(admin.value))

        val json = Json.encodeToString(PermissionSettings.serializer(), permissions.settings)
        val restored = Json.decodeFromString(PermissionSettings.serializer(), json)

        assertEquals(permissions.settings, restored)

        val reloaded = DefaultPermissionService(NoopLogger, localPlayer = { me }, initialSettings = restored)
        assertTrue(reloaded.can(member, Permission.MANAGE_SHOP_REWARDS))
        assertTrue(reloaded.shares(Permission.VIEW_EXACT_POSITION, admin))
        assertFalse(reloaded.shares(Permission.VIEW_EXACT_POSITION, member))
    }

    @Test
    fun `the default settings grant nothing to anybody`() {
        val fresh = DefaultPermissionService(NoopLogger, localPlayer = { me })
        assertEquals(emptySet<Permission>(), fresh.capabilitiesOf(stranger))
        assertEquals(GroupRole.GUEST, fresh.roleOf(stranger))
    }

    @Test
    fun `the kinds are what the enum says they are`() {
        assertEquals(PermissionKind.DISCLOSURE, Permission.VIEW_ISLAND.kind)
        assertEquals(PermissionKind.CAPABILITY, Permission.SEND_WAYPOINTS.kind)
    }
}
