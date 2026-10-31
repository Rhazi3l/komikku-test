package eu.kanade.tachiyomi.data.cover

// KMK -->
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import logcat.LogPriority
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Replaces the cover of a library manga with the latest volume cover in the chosen language (default Japanese)
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

    /**
     * Updates covers for the whole list, one title at a time to stay inside MangaBaka's rate limit.
     * Runs independently of the chapter-update restrictions (completed, unread, release window).
     * With [force] the 7-day wait between checks is ignored, for refreshes the user asked for.
     * [onProgress] is called at most every [PROGRESS_INTERVAL_MS] with the title being checked.
     */
    suspend fun updateAll(
        mangaList: List<Manga>,
        force: Boolean = false,
        onProgress: suspend (manga: Manga, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ) {
        if (!libraryPreferences.mangaBakaCovers().get()) return
        var lastProgress = 0L
        mangaList.forEachIndexed { done, manga ->
            currentCoroutineContext().ensureActive()
            if (now() - lastProgress >= PROGRESS_INTERVAL_MS) {
                lastProgress = now()
                onProgress(manga, done, mangaList.size)
            }
            update(manga, force)
        }
    }

    /** Never throws (except cancellation); any failure leaves the current cover untouched. */
    suspend fun update(manga: Manga, force: Boolean = false): Outcome {
        if (!libraryPreferences.mangaBakaCovers().get() || manga.isLocal() || !manga.favorite) return Outcome.Skipped
        return try {
            // File and network work, so never on the caller's (possibly main) thread.
            withIOContext { updateInternal(manga, force) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "MangaBaka cover update failed for '${manga.title}'" }
            Outcome.Failed
        }
    }

    /** What [update] did with a title, for feedback to the user. */
    sealed interface Outcome {
        data class Updated(val volume: String) : Outcome
        data object Unchanged : Outcome
        data object NoCovers : Outcome
        data object NotTracked : Outcome
        data object UserCover : Outcome
        data object CheckedRecently : Outcome
        data object Failed : Outcome
        data object Skipped : Outcome
    }

    private suspend fun updateInternal(manga: Manga, force: Boolean): Outcome {
        val language = libraryPreferences.mangaBakaCoverLanguage().get()
        val coverFile = coverCache.getCustomCoverFile(manga.id)
        val markerFile = File(coverFile.parentFile, coverFile.name + ".mb")
        val marker = readMarker(markerFile)

        // A custom cover we did not write (or that was changed since) belongs to the user.
        if (coverFile.exists() && (marker == null || marker.coverModified != coverFile.lastModified())) {
            return Outcome.UserCover
        }
        // Checked recently in this language (also covers titles MangaBaka has nothing for).
        val lastCheck = marker?.takeIf { it.checkedLanguage == language }?.checkedAt
        if (!force && lastCheck != null && now() - lastCheck < RECHECK_MS) return Outcome.CheckedRecently

        val tracks = getTracks.await(manga.id)
        val candidates = SOURCES.mapNotNull { (trackerId, source) ->
            val remoteId = tracks.firstOrNull { it.trackerId == trackerId }?.remoteId?.takeIf { it > 0 }
                ?: return@mapNotNull null
            // MangaBaka identifies MangaUpdates series by the base-36 form of the numeric ID.
            val id = if (trackerId == TrackerManager.MANGA_UPDATES) remoteId.toString(36) else remoteId.toString()
            source to id
        }
        if (candidates.isEmpty()) return Outcome.NotTracked

        // Try each linked tracker in order until MangaBaka knows the series.
        val seriesId = candidates.firstNotNullOfOrNull { (source, id) -> resolveSeriesId(source, id) }
        val latest = seriesId?.let { latestVolumeCover(it, language) }
        if (seriesId == null || latest == null) {
            // Remember "nothing found" so it is not looked up on every update. A cover we wrote earlier
            // (for example in another language) stays as it is.
            val checked = now()
            val kept = if (coverFile.exists()) marker?.copy(checkedAt = checked, checkedLanguage = language) else null
            writeMarker(markerFile, kept ?: Marker(0, "", checked, 0, language, language))
            return Outcome.NoCovers
        }
        val (index, imageUrl) = latest

        // The cover on disk is already this volume in this language.
        if (coverFile.exists() && marker != null && marker.isCover(seriesId, index, language)) {
            writeMarker(markerFile, marker.copy(checkedAt = now(), checkedLanguage = language))
            return Outcome.Unchanged
        }

        val bytes = get(imageUrl).use { it.body.bytes() }
        if (bytes.isEmpty()) return Outcome.Failed
        coverCache.setCustomCoverToCache(manga, bytes.inputStream())
        writeMarker(markerFile, Marker(seriesId, index, now(), coverFile.lastModified(), language, language))
        updateManga.awaitUpdateCoverLastModified(manga.id)
        return Outcome.Updated(index)
    }

    private suspend fun resolveSeriesId(source: String, id: String): Long? {
        val root = getJson("$API/v1/source/$source/$id") ?: return null
        val series = root["data"]?.jsonObject?.get("series")?.jsonArray ?: return null
        val active = series.map { it.jsonObject }.firstOrNull { it.str("state") == "active" }
        if (active != null) return active.long("id")
        // Follow a merge once.
        val mergedWith = series.map { it.jsonObject }.firstNotNullOfOrNull { it.long("merged_with") } ?: return null
        val merged = getJson("$API/v1/series/$mergedWith") ?: return mergedWith
        return merged["data"]?.jsonObject?.long("id") ?: mergedWith
    }

    /** Highest whole-number volume with a cover in [language], as (index, image URL). */
    private suspend fun latestVolumeCover(seriesId: Long, language: String): Pair<String, String>? {
        var best: Triple<Double, String, String>? = null
        var page = 1
        while (page <= MAX_PAGES) {
            val root = getJson("$API/v1/series/$seriesId/images?type=volume&language=$language&limit=50&page=$page")
                ?: break
            val items = root["data"] as? JsonArray ?: break
            for (item in items.map { it.jsonObject }) {
                // Use the label ("3") rather than index_numeric: alternate editions of volume 3 have 3.1 there.
                val number = item.str("index")?.toIntOrNull()?.toDouble() ?: continue // skips "2.5", "Box set"
                val image = item["image"]?.jsonObject ?: continue
                val url = image["x350"]?.jsonObject?.str("x2")
                    ?: image["raw"]?.jsonObject?.str("url")
                    ?: continue
                if (best == null || number > best.first) {
                    best = Triple(number, item.str("index") ?: number.toLong().toString(), url)
                }
            }
            val next = root["pagination"]?.jsonObject?.get("next")
            if (next == null || next is JsonNull) break
            page++
        }
        return best?.let { it.second to it.third }
    }

    /** Null means "not found" (HTTP 404). Any other failure throws, so it is not remembered as a miss. */
    private suspend fun getJson(url: String): JsonObject? {
        return try {
            get(url).use { json.parseToJsonElement(it.body.string()).jsonObject }
        } catch (e: HttpException) {
            if (e.code == HTTP_NOT_FOUND) null else throw e
        }
    }

    /**
     * GET, spaced so a whole-library pass stays under the API's 180 requests per minute, with one retry
     * after the server's 10 s back-off on HTTP 429. Throws [HttpException] on any other failure.
     */
    private suspend fun get(url: String): Response {
        delay(REQUEST_SPACING_MS)
        var response = client.newCall(Request.Builder().url(url).build()).await()
        if (response.code == HTTP_TOO_MANY_REQUESTS) {
            response.close()
            delay(RETRY_AFTER_MS)
            response = client.newCall(Request.Builder().url(url).build()).await()
        }
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code)
        }
        return response
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun now() = System.currentTimeMillis()

    /**
     * What this class last did for a title. [language] is the language of the cover on disk,
     * [checkedLanguage] the language last looked up (they differ when that language had no covers).
     * A miss is stored with seriesId 0.
     */
    private data class Marker(
        val seriesId: Long,
        val index: String,
        val checkedAt: Long,
        val coverModified: Long,
        val language: String,
        val checkedLanguage: String,
    ) {
        fun isCover(seriesId: Long, index: String, language: String) =
            this.seriesId == seriesId && this.index == index && this.language == language
    }

    private fun readMarker(file: File): Marker? = runCatching {
        val parts = file.readText().trim().split('|')
        val language = parts.getOrNull(4) ?: "ja" // markers from before the language option are Japanese
        val checkedLanguage = parts.getOrNull(5) ?: language
        // Markers from builds before the volume-label fix may point at the wrong volume: check those again.
        val checkedAt = if (parts.size >= 6) parts[2].toLong() else 0L
        Marker(parts[0].toLong(), parts[1], checkedAt, parts[3].toLong(), language, checkedLanguage)
    }.getOrNull()

    private fun writeMarker(file: File, m: Marker) {
        file.writeText("${m.seriesId}|${m.index}|${m.checkedAt}|${m.coverModified}|${m.language}|${m.checkedLanguage}")
    }

    private companion object {
        const val API = "https://api.mangabaka.org"

        /** Komikku tracker ID to MangaBaka source name, in order of preference. */
        val SOURCES = listOf(
            TrackerManager.ANILIST to "anilist",
            TrackerManager.MYANIMELIST to "my-anime-list",
            TrackerManager.KITSU to "kitsu",
            TrackerManager.MANGA_UPDATES to "manga-updates",
        )
        const val RECHECK_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_PAGES = 5
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val RETRY_AFTER_MS = 10_000L
        const val REQUEST_SPACING_MS = 350L
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
// KMK <--
