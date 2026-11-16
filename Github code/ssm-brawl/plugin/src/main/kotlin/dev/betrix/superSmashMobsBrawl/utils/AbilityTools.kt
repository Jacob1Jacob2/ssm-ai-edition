package dev.betrix.superSmashMobsBrawl.utils

import net.minecraft.core.component.DataComponents
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.ItemStack

/** Remove the synchronized vanilla use-on-block action, including client-side prediction. */
object AbilityTools {
    fun prepare(item: ItemStack): ItemStack {
        val handle = CraftItemStack.asNMSCopy(item)
        if (!handle.has(DataComponents.BLOCK_TRANSFORMER)) return item
        handle.remove(DataComponents.BLOCK_TRANSFORMER)
        return CraftItemStack.asBukkitCopy(handle)
    }
}
