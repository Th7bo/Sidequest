package dev.th7bo.sidequest.platform.core.permission

import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.permission.GroupRole
import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.permission.PermissionService
import dev.th7bo.sidequest.platform.permission.PermissionSettings
import dev.th7bo.sidequest.platform.player.PlayerId

/**
 * Decides permissions.
 *
 * Small, and deliberately so: the value of this class is that there is exactly one of it. A permission
 * check duplicated into a feature is a permission check that does not get updated when the rules
 * change, and the failure is silent — the feature keeps working, it just stops asking.
 *
 * The settings are held in memory and replaced wholesale by [update]. They arrive from storage on
 * startup and from the backend when the group changes them, and both paths are the same call, so
 * there is no ordering between them to get wrong.
 */
public class DefaultPermissionService(
    private val log: Logger,
    /**
     * The local player, needed to answer disclosure questions.
     *
     * A supplier because it is null before login and this service is built before that.
     */
    private val localPlayer: () -> PlayerId?,
    initialSettings: PermissionSettings = PermissionSettings.Default,
) : PermissionService {

    override var settings: PermissionSettings = initialSettings
        private set

    /** Replaces the settings. From storage on startup, or from the backend when the group edits them. */
    public fun update(settings: PermissionSettings) {
        this.settings = settings
        log.debug {
            "Permissions updated: ${settings.roles.size} role(s), " +
                "${settings.overrides.size} override(s), ${settings.disclosures.size} disclosure(s)"
        }
    }

    override fun roleOf(player: PlayerId): GroupRole =
        settings.roles[player.value] ?: GroupRole.GUEST

    override fun can(actor: PlayerId, permission: Permission): Boolean {
        if (!permission.isCapability) {
            // Asking the wrong question. Answered as no rather than as a plausible yes: a disclosure is
            // not the group's to grant, and a caller that got here has a bug worth finding.
            log.warn { "can() was asked about the disclosure $permission; use shares() instead" }
            return false
        }

        // The override wins in both directions. Granting a member an admin capability and denying an
        // admin one are both things a group needs, and a role-only model can do neither.
        settings.overrides[actor.value]?.get(permission)?.let { return it }

        return roleOf(actor).isAtLeast(permission.defaultRole)
    }

    override fun shares(permission: Permission, viewer: PlayerId): Boolean {
        if (!permission.isDisclosure) {
            log.warn { "shares() was asked about the capability $permission; use can() instead" }
            return false
        }

        // Sharing with ourselves is always allowed. Without this, the local HUD showing our own
        // position would be gated on us having agreed to share it with somebody.
        if (viewer.value == localPlayer()?.value) return true

        val audience = settings.disclosures[permission]
            // No entry means the permission's own default, which is off for anything that reveals where
            // a person physically is.
            ?: return permission.sharedByDefault

        return PermissionSettings.EVERYONE in audience || viewer.value in audience
    }

    override fun sharesWithAnybody(permission: Permission): Boolean {
        if (!permission.isDisclosure) return false
        val audience = settings.disclosures[permission] ?: return permission.sharedByDefault
        return audience.isNotEmpty()
    }

    override fun capabilitiesOf(actor: PlayerId): Set<Permission> =
        Permission.CAPABILITIES.filterTo(LinkedHashSet()) { can(actor, it) }

    // -- editing -----------------------------------------------------------

    /**
     * Sets somebody's role, returning the new settings.
     *
     * Returns rather than only mutating, so the caller can persist and sync the same value it applied.
     * Two calls that each read, edit and write would race; one that hands back the result cannot.
     */
    public fun setRole(player: PlayerId, role: GroupRole): PermissionSettings {
        update(settings.copy(roles = settings.roles + (player.value to role)))
        return settings
    }

    /** Grants, denies, or clears an override. Null clears it, falling back to the role. */
    public fun setOverride(player: PlayerId, permission: Permission, allowed: Boolean?): PermissionSettings {
        require(permission.isCapability) { "$permission is a disclosure and has no per-user override" }
        val existing = settings.overrides[player.value].orEmpty()
        val updated = if (allowed == null) existing - permission else existing + (permission to allowed)
        val overrides = if (updated.isEmpty()) {
            settings.overrides - player.value
        } else {
            settings.overrides + (player.value to updated)
        }
        update(settings.copy(overrides = overrides))
        return settings
    }

    /**
     * Sets who a disclosure is shared with.
     *
     * An empty set means nobody, which is different from an absent entry meaning "the default" — a
     * player who has explicitly turned something off must stay off when the default changes.
     */
    public fun setDisclosure(permission: Permission, audience: Set<String>): PermissionSettings {
        require(permission.isDisclosure) { "$permission is a capability, not a disclosure" }
        update(settings.copy(disclosures = settings.disclosures + (permission to audience)))
        return settings
    }

    /** Shares a disclosure with the whole group, or with nobody. */
    public fun setSharedWithEveryone(permission: Permission, shared: Boolean): PermissionSettings =
        setDisclosure(permission, if (shared) setOf(PermissionSettings.EVERYONE) else emptySet())

    /** Forgets somebody entirely: their role and their overrides. For leaving the group. */
    public fun forget(player: PlayerId): PermissionSettings {
        update(
            settings.copy(
                roles = settings.roles - player.value,
                overrides = settings.overrides - player.value,
            ),
        )
        return settings
    }
}
