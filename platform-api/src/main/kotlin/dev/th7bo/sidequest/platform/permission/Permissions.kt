package dev.th7bo.sidequest.platform.permission

import dev.th7bo.sidequest.platform.player.PlayerId
import kotlinx.serialization.Serializable

/**
 * What somebody is in the friend group.
 *
 * Ordered, so `>=` works and a check reads as "admin or better". [GUEST] is the floor and is what an
 * unknown player is: somebody the group has not decided about gets the least, not the default.
 */
@Serializable
public enum class GroupRole(public val displayName: String) {
    GUEST("Guest"),
    MEMBER("Member"),
    ADMIN("Admin"),
    OWNER("Owner"),
    ;

    public fun isAtLeast(other: GroupRole): Boolean = ordinal >= other.ordinal
}

/**
 * Two different questions, and the plan's flat list hides the difference.
 *
 * "Send pings" is about what *I* am allowed to do. "View exact position" is about what somebody has
 * allowed to be known about *them*. Treating both as one role check produces a privacy gate that is
 * decorative: an admin would be able to see a member's exact position because admins can do things,
 * which is not what a member agreeing to share their island agreed to.
 *
 * So each permission says which kind it is, and the service answers a different question for each.
 */
@Serializable
public enum class PermissionKind {
    /**
     * Something a person may do, decided by their role in the group.
     *
     * Asked as "can this actor do this". Sending a ping, creating a debt, uploading evidence.
     */
    CAPABILITY,

    /**
     * Something a person may allow to be known about them, decided by them.
     *
     * Asked as "does the subject share this with that viewer". No role overrides it, because it is
     * not the group's to decide.
     */
    DISCLOSURE,
}

/**
 * Everything the group can be permissioned on.
 *
 * The full list from the plan, each with the role it takes by default and — for a disclosure — whether
 * it is off until explicitly turned on.
 *
 * The defaults are the interesting part. Anything destructive or attributable to somebody else needs
 * [GroupRole.ADMIN]; anything ordinary is [GroupRole.MEMBER]; a guest can do almost nothing. And the
 * two disclosures that reveal where a person physically is default to *off*, because a privacy default
 * that has to be turned off is not a privacy default.
 */
@Serializable
public enum class Permission(
    public val kind: PermissionKind,
    /** For a capability: the lowest role that has it. */
    public val defaultRole: GroupRole = GroupRole.MEMBER,
    /** For a disclosure: whether it is shared unless turned off. */
    public val sharedByDefault: Boolean = false,
    public val displayName: String,
) {
    // -- what people may see about you --------------------------------------

    VIEW_ONLINE_STATUS(
        PermissionKind.DISCLOSURE,
        sharedByDefault = true,
        displayName = "See when you are online",
    ),
    VIEW_ACTIVITY(
        PermissionKind.DISCLOSURE,
        sharedByDefault = true,
        displayName = "See what you are doing",
    ),
    VIEW_ISLAND(
        PermissionKind.DISCLOSURE,
        sharedByDefault = true,
        displayName = "See which island you are on",
    ),

    /**
     * Off by default, and the reason [PermissionKind.DISCLOSURE] exists.
     *
     * Exact coordinates are the one thing here that lets somebody find a person rather than know about
     * them. It is a reasonable thing to share with a friend and an unreasonable thing to share by
     * accident, so nothing shares it until somebody says so.
     */
    VIEW_EXACT_POSITION(
        PermissionKind.DISCLOSURE,
        sharedByDefault = false,
        displayName = "See your exact position",
    ),

    // -- what people may do -------------------------------------------------

    SEND_PINGS(PermissionKind.CAPABILITY, GroupRole.MEMBER, displayName = "Send pings"),
    SEND_WAYPOINTS(PermissionKind.CAPABILITY, GroupRole.MEMBER, displayName = "Share waypoints"),
    CREATE_READY_CHECKS(PermissionKind.CAPABILITY, GroupRole.MEMBER, displayName = "Start ready checks"),

    /** A debt is a claim about somebody else's obligations, so it is not a guest's to make. */
    CREATE_DEBTS(PermissionKind.CAPABILITY, GroupRole.MEMBER, displayName = "Create debts"),

    /**
     * Confirming a payment settles a debt, which is the half that costs somebody something.
     *
     * Admin, because a member confirming their own payment would make the ledger a formality.
     */
    CONFIRM_PAYMENTS(PermissionKind.CAPABILITY, GroupRole.ADMIN, displayName = "Confirm payments"),

    UPLOAD_EVIDENCE(PermissionKind.CAPABILITY, GroupRole.MEMBER, displayName = "Upload evidence"),

    /** Editing somebody else's evidence is rewriting the record, not adding to it. */
    EDIT_EVIDENCE(PermissionKind.CAPABILITY, GroupRole.ADMIN, displayName = "Edit evidence"),

    ASSIGN_TITLES(PermissionKind.CAPABILITY, GroupRole.ADMIN, displayName = "Assign titles"),
    ASSIGN_JOKE_COSMETICS(PermissionKind.CAPABILITY, GroupRole.ADMIN, displayName = "Assign joke cosmetics"),

    /**
     * A synchronised sound plays in somebody else's ears.
     *
     * Member, because that is the point of a soundboard, and rate-limited elsewhere — the permission
     * says who may, not how often.
     */
    TRIGGER_SOUNDS(PermissionKind.CAPABILITY, GroupRole.MEMBER, displayName = "Trigger synced sounds"),

    MODERATE_CONTENT(PermissionKind.CAPABILITY, GroupRole.ADMIN, displayName = "Moderate group content"),
    CREATE_ACHIEVEMENTS(PermissionKind.CAPABILITY, GroupRole.ADMIN, displayName = "Create achievements"),

    /** Rewards are currency. Owner only. */
    MANAGE_SHOP_REWARDS(PermissionKind.CAPABILITY, GroupRole.OWNER, displayName = "Manage shop rewards"),
    ;

    public val isCapability: Boolean get() = kind == PermissionKind.CAPABILITY

    public val isDisclosure: Boolean get() = kind == PermissionKind.DISCLOSURE

    public companion object {
        public val CAPABILITIES: List<Permission> = entries.filter { it.isCapability }
        public val DISCLOSURES: List<Permission> = entries.filter { it.isDisclosure }
    }
}

/**
 * The group's permission settings, as stored — and the evaluation of them.
 *
 * **The rules live here, not in the service.** The client asks about itself and the server asks about
 * everybody, and if each had its own copy of the logic a permission would eventually mean two different
 * things on the two sides of a connection. A disagreement about a permission is a leak, so there is one
 * implementation and both sides call it.
 *
 * Disclosures are keyed by *subject* for the same reason. A client holds settings with one subject in
 * them — itself — and the server holds every member's; the shape is identical, so the same code answers
 * both.
 */
@Serializable
public data class PermissionSettings(
    /** Role per account. Anybody absent is a [GroupRole.GUEST]. */
    public val roles: Map<String, GroupRole> = emptyMap(),
    /**
     * Per-user capability overrides, as the plan asks for.
     *
     * Overrides beat the role in both directions: a member can be granted an admin capability, and an
     * admin can be denied one. Both are needed — the first for "let them run ready checks", the second
     * for the person who cannot be trusted with the soundboard.
     */
    public val overrides: Map<String, Map<Permission, Boolean>> = emptyMap(),
    /**
     * Who each person shares what with: subject, then permission, then audience.
     *
     * An absent entry means the permission's own default. An **empty** audience means explicitly nobody,
     * which is a different thing: somebody who has turned a disclosure off must stay off if the default
     * ever changes.
     */
    public val disclosures: Map<String, Map<Permission, Set<String>>> = emptyMap(),
) {

    /** [subject]'s role. [GroupRole.GUEST] for anybody the group has not decided about. */
    public fun roleOf(subject: String): GroupRole = roles[subject] ?: GroupRole.GUEST

    /**
     * Whether [actor] may do [permission].
     *
     * Returns false for a disclosure rather than a plausible answer — a caller asking this about one has
     * a bug, and a helpful answer would hide it while leaking the thing the split protects.
     */
    public fun can(actor: String, permission: Permission): Boolean {
        if (!permission.isCapability) return false
        overrides[actor]?.get(permission)?.let { return it }
        return roleOf(actor).isAtLeast(permission.defaultRole)
    }

    /**
     * Whether [subject] shares [permission] with [viewer].
     *
     * Sharing with yourself is always allowed; without that, a local HUD showing your own position would
     * be gated on you having agreed to share it with somebody.
     */
    public fun shares(subject: String, permission: Permission, viewer: String): Boolean {
        if (!permission.isDisclosure) return false
        if (subject == viewer) return true
        val audience = disclosures[subject]?.get(permission) ?: return permission.sharedByDefault
        return EVERYONE in audience || viewer in audience
    }

    /** Whether [subject] shares [permission] with anybody at all. */
    public fun sharesWithAnybody(subject: String, permission: Permission): Boolean {
        if (!permission.isDisclosure) return false
        val audience = disclosures[subject]?.get(permission) ?: return permission.sharedByDefault
        return audience.isNotEmpty()
    }

    /** Everything [actor] may currently do. */
    public fun capabilitiesOf(actor: String): Set<Permission> =
        Permission.CAPABILITIES.filterTo(LinkedHashSet()) { can(actor, it) }

    public companion object {
        /** Stands in for "the whole group" in [disclosures]. Not a valid UUID, so it cannot collide. */
        public const val EVERYONE: String = "*"

        public val Default: PermissionSettings = PermissionSettings()
    }
}

/**
 * The one place a permission is decided.
 *
 * Every outbound feature asks before it acts, and every inbound one asks before it reveals. Scattering
 * the checks would mean the privacy settings applied to whichever features remembered them, which is
 * indistinguishable from not having them.
 */
public interface PermissionService {

    /** The stored settings. */
    public val settings: PermissionSettings

    /** Somebody's role. [GroupRole.GUEST] for anybody the group has not decided about. */
    public fun roleOf(player: PlayerId): GroupRole

    /**
     * Whether [actor] may do [permission].
     *
     * For [PermissionKind.CAPABILITY] only. Asking this about a disclosure is a programming error and
     * returns false rather than a plausible answer — a disclosure is not the group's to grant.
     */
    public fun can(actor: PlayerId, permission: Permission): Boolean

    /**
     * Whether the local player shares [permission] with [viewer].
     *
     * For [PermissionKind.DISCLOSURE] only. The gate the sync layer asks before putting anything about
     * us on the wire.
     */
    public fun shares(permission: Permission, viewer: PlayerId): Boolean

    /** Whether the local player shares [permission] with anybody at all. */
    public fun sharesWithAnybody(permission: Permission): Boolean

    /** Everything [actor] may currently do. For a permissions screen. */
    public fun capabilitiesOf(actor: PlayerId): Set<Permission>
}
