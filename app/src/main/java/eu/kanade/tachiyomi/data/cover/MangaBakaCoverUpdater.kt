package eu.kanade.tachiyomi.data.cover

// KMK -->
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Replaces the cover of a library manga with the latest original-language (Japanese) volume cover
 * from MangaBaka (https://mangabaka.org). Covers are written to the existing custom cover slot, so
 * the source extension is not touched. A marker file next to the cover records what this class
 * wrote, so a cover the user set by hand is never overwritten.
 *
 * Cover data courtesy of MangaBaka and its underlying providers (CC BY-NC-SA 4.0).
 */
class MangaBakaCoverUpdater(
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val networkHelper: NetworkHelper = Injekt.get(),
) {

    private val client get() = networkHelper.client
    private val json = Json { ignoreUnknownKeys = true }

    /** Never throws (except cancellation); any failure leaves the current cover untouched. */
    suspend fun update(manga: Manga) {
        if (!libraryPreferences.mangaBakaCovers().get() || manga.isLocal() || !manga.favorite) return
        try {
            updateInternal(manga)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "MangaBaka cover update failed for '${manga.title}'" }
        }
    }

    private suspend fun updateInternal(manga: Manga) {
        val coverFile = coverCache.getCustomCoverFile(manga.id)
        val markerFile = File(coverFile.parentFile, coverFile.name + ".mb")
        val marker = readMarker(markerFile)

        if (coverFile.exists()) {
            // A custom cover we did not write (or that was changed since) belongs to the user.
            if (marker == null || marker.coverModified != coverFile.lastModified()) return
            if (System.currentTimeMillis() - marker.checkedAt < RECHECK_MS) return
        }

        val anilistId = getTracks.await(manga.id)
            .firstOrNull { it.trackerId == TrackerManager.ANILIST }
            ?.remoteId
            ?.takeIf { it > 0 }
            ?: return

        val seriesId = resolveSeriesId(anilistId) ?: return
        val (index, imageUrl) = latestVolumeCover(seriesId) ?: return

        if (coverFile.exists() && marker?.seriesId == seriesId && marker.index == index) {
            writeMarker(markerFile, Marker(seriesId, index, System.currentTimeMillis(), coverFile.lastModified()))
            return
        }

        val bytes = get(imageUrl).use { it.body.bytes() }
        if (bytes.isEmpty()) return
        coverCache.setCustomCoverToCache(manga, bytes.inputStream())
        writeMarker(markerFile, Marker(seriesId, index, System.currentTimeMillis(), coverFile.lastModified()))
        updateManga.awaitUpdateCoverLastModified(manga.id)
    }

    private suspend fun resolveSeriesId(anilistId: Long): Long? {
        val root = getJson("$API/v1/source/anilist/$anilistId") ?: return null
        val series = root["data"]?.jsonObject?.get("series")?.jsonArray ?: return null
        val active = series.map { it.jsonObject }.firstOrNull { it.str("state") == "active" }
        if (active != null) return active.long("id")
        // Follow a merge once.
        val mergedWith = series.map { it.jsonObject }.firstNotNullOfOrNull { it.long("merged_with") } ?: return null
        val merged = getJson("$API/v1/series/$mergedWith") ?: return mergedWith
        return merged["data"]?.jsonObject?.long("id") ?: mergedWith
    }

    /** Highest whole-number volume with a Japanese cover, as (index, image URL). */
    private suspend fun latestVolumeCover(seriesId: Long): Pair<String, String>? {
        var best: Triple<Double, String, String>? = null
        var page = 1
        while (page <= MAX_PAGES) {
            val root = getJson("$API/v1/series/$seriesId/images?type=volume&language=ja&limit=50&page=$page")
                ?: break
            val items = root["data"] as? JsonArray ?: break
            for (item in items.map { it.jsonObject }) {
                val number = item["index_numeric"]?.jsonPrimitive?.doubleOrNull ?: continue
                if (number % 1.0 != 0.0) continue // skip 1.1, 2.5 and similar
                val image = item["image"]?.jsonObject ?: continue
                val url = image["x350"]?.jsonObject?.str("x2")
                    ?: image["raw"]?.jsonObject?.str("url")
                    ?: continue
                if (best == null || number > best.first) {
                    best = Triple(number, item.str("index") ?: number.toLong().toString(), url)
                }
            }
            val next = root["pagination"]?.jsonObject?.get("next")
            if (next == null || next is kotlinx.serialization.json.JsonNull) break
            page++
        }
        return best?.let { it.second to it.third }
    }

    private suspend fun getJson(url: String): JsonObject? {
        return try {
            get(url).use { json.parseToJsonElement(it.body.string()).jsonObject }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG, e) { "MangaBaka request failed: $url" }
            null
        }
    }

    private suspend fun get(url: String) = client.newCall(Request.Builder().url(url).build()).awaitSuccess()

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private data class Marker(val seriesId: Long, val index: String, val checkedAt: Long, val coverModified: Long)

    private fun readMarker(file: File): Marker? = runCatching {
        val parts = file.readText().split('|')
        Marker(parts[0].toLong(), parts[1], parts[2].toLong(), parts[3].toLong())
    }.getOrNull()

    private fun writeMarker(file: File, marker: Marker) {
        file.writeText("${marker.seriesId}|${marker.index}|${marker.checkedAt}|${marker.coverModified}")
    }

    private companion object {
        const val API = "https://api.mangabaka.org"
        const val RECHECK_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_PAGES = 5
    }
}
// KMK <--
