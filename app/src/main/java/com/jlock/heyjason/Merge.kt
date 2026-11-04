package com.jlock.heyjason

/**
 * Pure merge rules for combining this phone's data with the copy in Google Drive.
 * Each reminder / shopping item is merged on its own: the most recent edit wins,
 * and a deletion wins over any edit made before it.
 */
object Merge {
    /** Forget deletion records after this long — both phones will have long since caught up. */
    const val KEEP_DELETED_MS = 60L * 24 * 60 * 60 * 1000

    data class Result<K, T>(val items: List<T>, val deleted: Map<K, Long>)

    fun <K, T> merge(
        local: List<T>,
        localDeleted: Map<K, Long>,
        remote: List<T>,
        remoteDeleted: Map<K, Long>,
        key: (T) -> K,
        updated: (T) -> Long,
        now: Long = System.currentTimeMillis(),
    ): Result<K, T> {
        val deleted = HashMap<K, Long>()
        for ((k, t) in localDeleted) deleted[k] = maxOf(t, deleted[k] ?: 0L)
        for ((k, t) in remoteDeleted) deleted[k] = maxOf(t, deleted[k] ?: 0L)

        val byKey = LinkedHashMap<K, T>()
        for (r in remote) byKey[key(r)] = r
        for (l in local) {
            val r = byKey[key(l)]
            // Ties go to this phone's copy.
            if (r == null || updated(l) >= updated(r)) byKey[key(l)] = l
        }

        val items = mutableListOf<T>()
        for ((k, item) in byKey) {
            val d = deleted[k]
            if (d != null && d >= updated(item)) continue // deleted after its last edit
            if (d != null) deleted.remove(k) // edited after the deletion → it's back
            items += item
        }
        val pruned = deleted.filterValues { now - it < KEEP_DELETED_MS }
        return Result(items, pruned)
    }

    fun reminders(
        local: List<Reminder>, localDeleted: Map<Int, Long>,
        remote: List<Reminder>, remoteDeleted: Map<Int, Long>,
        now: Long = System.currentTimeMillis(),
    ) = merge(local, localDeleted, remote, remoteDeleted, { it.id }, { it.updatedAt }, now)

    fun shopping(
        local: List<ShopItem>, localDeleted: Map<Long, Long>,
        remote: List<ShopItem>, remoteDeleted: Map<Long, Long>,
        now: Long = System.currentTimeMillis(),
    ) = merge(local, localDeleted, remote, remoteDeleted, { it.id }, { it.updatedAt }, now)

    /** "Buy again" counts: keep the higher count seen on either phone. */
    fun history(local: Map<String, Int>, remote: Map<String, Int>): Map<String, Int> {
        val out = HashMap(remote)
        for ((k, v) in local) out[k] = maxOf(v, out[k] ?: 0)
        return out
    }

    /**
     * When a phone joins sync for the first time, its reminder ids may clash with the other
     * phone's (both used to count 1, 2, 3…). Returns old id → new id for every local reminder
     * that must be renumbered.
     */
    fun clashingIds(
        local: List<Reminder>,
        remote: List<Reminder>,
        remoteDeleted: Map<Int, Long>,
        pickNew: (Set<Int>) -> Int,
    ): Map<Int, Int> {
        val remoteById = remote.associateBy { it.id }
        val taken = HashSet<Int>().apply {
            addAll(local.map { it.id }); addAll(remote.map { it.id }); addAll(remoteDeleted.keys)
        }
        val out = LinkedHashMap<Int, Int>()
        for (l in local) {
            val r = remoteById[l.id]
            val clash = (r != null && r != l) || (r == null && l.id in remoteDeleted)
            if (clash) {
                val n = pickNew(taken)
                taken += n
                out[l.id] = n
            }
        }
        return out
    }
}
