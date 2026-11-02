package com.rysal.happytv.data.dao

import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.VodStream
import kotlinx.coroutines.yield

/** What [syncCatalogStream] actually wrote; zero upserted and removed means the table was left untouched. */
data class CatalogWrite(val upserted: Int, val removed: Int, val unchanged: Int) {
    val touched: Boolean get() = upserted > 0 || removed > 0

    /** Distinct rows the provider listed. */
    val total: Int get() = upserted + unchanged
}

/** Incoming rows compared and written per short transaction while syncing a catalog. */
internal const val CATALOG_UPSERT_CHUNK = 1_000

/** Ids per `WHERE id IN (...)` lookup or delete (SQLite binds at most 999 on older Android). */
internal const val CATALOG_DELETE_CHUNK = 500

/** Stored ids read per page while looking for rows the provider dropped. */
internal const val CATALOG_ID_PAGE = 2_000

/**
 * The ids a sync has seen, kept compact (#130). Provider ids are almost always
 * plain decimal numbers; those go into a growable `LongArray` (8 bytes each, no
 * boxing), anything else falls back to a string set. Membership is only asked
 * after the last id was added, so the array is sorted once and binary searched.
 */
internal class SeenIds {
    private var numeric = LongArray(1024)
    private var numericSize = 0
    private var sorted = false
    private val other = HashSet<String>()

    fun add(id: String) {
        val n = id.canonicalLongOrNull()
        if (n == null) {
            other.add(id)
            return
        }
        if (numericSize == numeric.size) numeric = numeric.copyOf(numeric.size * 2)
        numeric[numericSize++] = n
        sorted = false
    }

    operator fun contains(id: String): Boolean {
        val n = id.canonicalLongOrNull() ?: return id in other
        sortIfNeeded()
        return java.util.Arrays.binarySearch(numeric, 0, numericSize, n) >= 0
    }

    /** Number of distinct ids added. */
    val distinctCount: Int
        get() {
            sortIfNeeded()
            var distinct = 0
            for (i in 0 until numericSize) if (i == 0 || numeric[i] != numeric[i - 1]) distinct++
            return distinct + other.size
        }

    private fun sortIfNeeded() {
        if (!sorted) {
            java.util.Arrays.sort(numeric, 0, numericSize)
            sorted = true
        }
    }

    /** Only ids that print back identically ("007" and "+7" must not collide with "7"). */
    private fun String.canonicalLongOrNull(): Long? {
        if (isEmpty() || length > 18) return null
        val n = toLongOrNull() ?: return null
        return if (n.toString() == this) n else null
    }
}

/**
 * Brings a catalog table in line with the provider's full list **writing only
 * what differs** (#43), without ever holding the list in memory (#130).
 *
 * [incoming] is consumed lazily, [CATALOG_UPSERT_CHUNK] rows at a time. For each
 * chunk the stored rows with those ids are loaded ([load]), compared, and only
 * new or changed rows are upserted, each chunk in its own short transaction
 * (never one transaction across the sync, F-65). Ids seen go into a compact
 * [SeenIds]. Only after [incoming] ended normally are stored ids paged through
 * ([idsAfter], keyset on the id) and the ones the provider dropped deleted. If
 * [incoming] throws midway (network or decode failure) nothing is removed: the
 * rows already upserted are fresh provider data and every other row is intact.
 *
 * Stored rows are matched by id; incoming duplicates behave like the old
 * `REPLACE` insert (the last one wins).
 */
internal suspend fun <T> syncCatalogStream(
    incoming: Sequence<T>,
    id: (T) -> String,
    load: suspend (List<String>) -> List<T>,
    upsert: suspend (List<T>) -> Unit,
    idsAfter: suspend (after: String, limit: Int) -> List<String>,
    delete: suspend (List<String>) -> Unit,
): CatalogWrite {
    val seen = SeenIds()
    var upserted = 0

    suspend fun flush(chunk: LinkedHashMap<String, T>) {
        if (chunk.isEmpty()) return
        val stored = HashMap<String, T>(chunk.size * 2)
        for (slice in chunk.keys.chunked(CATALOG_DELETE_CHUNK)) {
            for (row in load(slice)) stored[id(row)] = row
        }
        val changed = ArrayList<T>()
        for ((key, row) in chunk) if (stored[key] != row) changed.add(row)
        if (changed.isNotEmpty()) {
            upsert(changed)
            upserted += changed.size
        }
        chunk.clear()
        yield()
    }

    val chunk = LinkedHashMap<String, T>(CATALOG_UPSERT_CHUNK * 2)
    for (row in incoming) {
        val key = id(row)
        seen.add(key)
        chunk.remove(key) // a later duplicate wins
        chunk[key] = row
        if (chunk.size >= CATALOG_UPSERT_CHUNK) flush(chunk)
    }
    flush(chunk)

    // The whole list arrived; now drop what the provider no longer lists.
    var removed = 0
    var after = ""
    while (true) {
        val page = idsAfter(after, CATALOG_ID_PAGE)
        if (page.isEmpty()) break
        after = page.last()
        val gone = page.filter { it !in seen }
        for (slice in gone.chunked(CATALOG_DELETE_CHUNK)) {
            delete(slice)
            removed += slice.size
        }
        yield()
    }
    val distinct = seen.distinctCount
    return CatalogWrite(
        upserted = upserted,
        removed = removed,
        unchanged = (distinct - upserted).coerceAtLeast(0),
    )
}

/**
 * Refresh write for a lazily decoded list (#130): never more than a chunk of rows in memory,
 * and nothing is removed unless [streams] ended normally. See [syncCatalogStream].
 */
suspend fun LiveStreamDao.syncStream(streams: Sequence<LiveStream>): CatalogWrite = syncCatalogStream(
    incoming = streams,
    id = { it.id },
    load = { getByIds(it) },
    upsert = { insertAll(it) },
    idsAfter = { after, limit -> idsAfter(after, limit) },
    delete = { deleteByIds(it) },
)

/**
 * Refresh write for a lazily decoded list (#130): never more than a chunk of rows in memory,
 * and nothing is removed unless [streams] ended normally. See [syncCatalogStream].
 */
suspend fun VodStreamDao.syncStream(streams: Sequence<VodStream>): CatalogWrite = syncCatalogStream(
    incoming = streams,
    id = { it.id },
    load = { getByIds(it) },
    upsert = { insertAll(it) },
    idsAfter = { after, limit -> idsAfter(after, limit) },
    delete = { deleteByIds(it) },
)

/**
 * Refresh write for a lazily decoded list (#130): never more than a chunk of rows in memory,
 * and nothing is removed unless [streams] ended normally. See [syncCatalogStream].
 */
suspend fun SeriesStreamDao.syncStream(streams: Sequence<SeriesStream>): CatalogWrite = syncCatalogStream(
    incoming = streams,
    id = { it.id },
    load = { getByIds(it) },
    upsert = { insertAll(it) },
    idsAfter = { after, limit -> idsAfter(after, limit) },
    delete = { deleteByIds(it) },
)
