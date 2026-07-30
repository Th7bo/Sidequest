package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.item.ItemRarity
import dev.th7bo.sidequest.platform.item.SqItem
import dev.th7bo.sidequest.platform.permission.Permission
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.storage.StorageScope
import kotlinx.coroutines.runBlocking
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Storage and permissions, in the real installation.
 *
 * The storage layer is covered headlessly against a temporary directory, which is where atomicity,
 * quarantine and recovery belong. What that cannot cover is the one thing that goes wrong in the
 * field: whether the directory the mod actually writes to exists and is writable. A store that works
 * perfectly under `@TempDir` and cannot create its own root in the game is a store that works in
 * every test and loses every user's data.
 *
 * The permission half is here because a privacy default that is only correct in a unit test is not a
 * privacy default. It is asserted against the service the mod really built.
 */
class StorageAndPermissionsTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)

        context.runOnClient<RuntimeException> {
            val storage = Sidequest.platform.storage
            // Stored as an `SqItem`, which is a real payload rather than a synthetic one: the item
            // snapshot's entire reason for existing is that it can be written down and read back.
            val repository = storage.repository(
                id = SqId.sidequest("gametest.trophy"),
                scope = StorageScope.Global,
                serializer = SqItem.serializer(),
                default = { SqItem.vanilla("minecraft:air", quantity = 0) },
            )

            runBlocking {
                // 1. A first load is empty rather than a failure, even before the directory exists.
                val first = repository.load()
                check(first.report.wasEmpty || first.report.isClean) {
                    "A first load reported ${first.report}"
                }

                // 2. Writing creates whatever directories are needed, in the real config tree.
                val hyperion = SqItem(
                    minecraftId = "minecraft:diamond_sword",
                    skyblockId = "HYPERION",
                    displayName = "§dWithered Hyperion",
                    rarity = ItemRarity.MYTHIC,
                )
                repository.save(hyperion)

                val loaded = repository.load()
                check(loaded.value == hyperion) { "Read back ${loaded.value.summary()}" }
                check(loaded.report.isClean) { "The load reported ${loaded.report}" }

                // 3. Update is read-modify-write in one step, which is what features use.
                val updated = repository.update { it.copy(quantity = it.quantity + 1) }
                check(updated.quantity == 2) { "Update produced ${updated.quantity}" }

                // 4. And the whole thing can be removed again, so the test leaves nothing behind.
                check(repository.delete()) { "Nothing was there to delete" }
                check(repository.load().report.wasEmpty) { "The file survived being deleted" }
            }
        }

        // 5. The permission defaults, on the service the mod built rather than one a test made.
        context.runOnClient<RuntimeException> {
            val permissions = Sidequest.platform.permissions
            val somebody = PlayerId("11111111-1111-4111-8111-111111111111")

            // Nobody is in the group yet, so nobody can do anything.
            check(!permissions.can(somebody, Permission.SEND_PINGS)) {
                "A stranger could send pings on a fresh install"
            }
            check(permissions.capabilitiesOf(somebody).isEmpty()) {
                "A stranger has capabilities: ${permissions.capabilitiesOf(somebody)}"
            }

            // And the one disclosure that lets somebody find a person is off.
            check(!permissions.shares(Permission.VIEW_EXACT_POSITION, somebody)) {
                "Exact position was shared by default"
            }
            check(permissions.shares(Permission.VIEW_ONLINE_STATUS, somebody)) {
                "Online status should be shared by default"
            }
        }

        context.waitTicks(SETTLE_TICKS)
    }

    private companion object {
        const val SETTLE_TICKS = 10
    }
}
