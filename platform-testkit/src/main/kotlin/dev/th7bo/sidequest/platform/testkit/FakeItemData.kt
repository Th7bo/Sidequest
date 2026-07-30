package dev.th7bo.sidequest.platform.testkit

import dev.th7bo.sidequest.platform.item.ItemDataSource

/**
 * An item's Hypixel data, from a map.
 *
 * The reason [ItemDataSource] is an interface. Every attribute name in the item reader is a
 * fact about Hypixel that will need correcting one day, and correcting it should mean running
 * a test rather than launching Minecraft and finding the right item.
 *
 * Nest one of these as a value to stand in for a nested tag:
 *
 * ```kotlin
 * FakeItemData("id" to "HYPERION", "gems" to FakeItemData("JADE_0" to "PERFECT"))
 * ```
 */
public class FakeItemData(private val values: Map<String, Any>) : ItemDataSource {

    public constructor(vararg entries: Pair<String, Any>) : this(entries.toMap())

    override fun string(key: String): String? = values[key] as? String

    override fun int(key: String): Int? = when (val value = values[key]) {
        is Int -> value
        is Byte -> value.toInt()
        is Long -> value.toInt()
        else -> null
    }

    override fun long(key: String): Long? = when (val value = values[key]) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }

    override fun byte(key: String): Byte? = when (val value = values[key]) {
        is Byte -> value
        is Int -> value.toByte()
        is Boolean -> if (value) 1 else 0
        else -> null
    }

    override fun compound(key: String): ItemDataSource? = values[key] as? ItemDataSource

    override fun keys(): Set<String> = values.keys

    override fun asString(key: String): String? = values[key]?.takeUnless { it is ItemDataSource }?.toString()
}
