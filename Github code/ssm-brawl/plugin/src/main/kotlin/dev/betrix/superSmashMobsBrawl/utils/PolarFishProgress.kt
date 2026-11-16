package dev.betrix.superSmashMobsBrawl.utils

/** Progress belongs to one life. A miss is a whole volley with no accepted enemy hit. */
class PolarFishProgress {
    var stacks = 0
        private set
    var salmonStacks = 0
        private set
    var ultra = false
        private set
    var misses = 0
        private set
    val fishCount get() = if (ultra) 1 else (stacks + 1).coerceAtMost(8)
    val cooldown get() = if (ultra) (50 - salmonStacks).coerceAtLeast(5) / 100.0 else (40 - stacks * 2) / 10.0
    val progress get() = if (ultra) salmonStacks / 45f else stacks / 8f
    // Strictly above each 10% boundary adds one more miss of protection.
    val missesRequired get() = if (ultra) ((salmonStacks * 10 + 44) / 45).coerceAtLeast(1) else 1
    fun startUltra() { ultra = true; salmonStacks = 0; misses = 0 }
    fun endUltra() { ultra = false; salmonStacks = 0; misses = 0 }
    fun collect(salmon: Boolean): Boolean {
        if (salmon != ultra) return false
        if (ultra) salmonStacks = (salmonStacks + 1).coerceAtMost(45)
        else stacks = (stacks + 1).coerceAtMost(8)
        return true
    }
    fun resolveVolley(hit: Boolean, salmon: Boolean) {
        if (salmon != ultra) return
        if (hit) { misses = 0; return }
        if (++misses < missesRequired) return
        misses = 0
        if (ultra) salmonStacks = (salmonStacks - 1).coerceAtLeast(0)
        else stacks = (stacks - 2).coerceAtLeast(0)
    }
}
