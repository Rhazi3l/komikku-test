package eu.kanade.tachiyomi.data.cover

// KMK -->
import android.app.Application
import androidx.core.app.NotificationCompat
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.system.notify
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
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.libraryUpdateError.interactor.InsertLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.model.LibraryUpdateError
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.InsertLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.model.LibraryUpdateErrorMessage
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.kmk.KMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException

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
    private val application: Application = Injekt.get(),
    private val insertLibraryUpdateErrors: InsertLibraryUpdateErrors = Injekt.get(),
    private val insertLibraryUpdateErrorMessages: InsertLibraryUpdateErrorMessages = Injekt.get(),
) {

    private val client get() = networkHelper.client
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Updates covers for every title in the list, one at a time to stay inside MangaBaka's rate limit.
     * Runs independently of the chapter-update restrictions (completed, unread, release window).
     * With [force] the daily wait between checks is ignored, for refreshes the user asked for.
     * [onProgress] is called at most every [PROGRESS_INTERVAL_MS] with the title being checked.
     *
     * Failures are written to the library update error list, like failed chapter updates, except for
     * titles in [keepErrorsFor] (titles whose chapter update already failed keep that error).
     * A summary notification follows a forced pass, or any pass that changed or failed something.
     * @return the number of titles that failed.
     */
    suspend fun updateAll(
        mangaList: List<Manga>,
        force: Boolean = false,
        keepErrorsFor: Set<Long> = emptySet(),
        onProgress: suspend (manga: Manga, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): Int {
        if (!libraryPreferences.mangaBakaCovers().get()) return 0
        var lastProgress = 0L
        var updated = 0
        var unchanged = 0
        var noCovers = 0
        var notTracked = 0
        var userCovers = 0
        var failed = 0
        mangaList.forEachIndexed { done, manga ->
            currentCoroutineContext().ensureActive()
            if (now() - lastProgress >= PROGRESS_INTERVAL_MS) {
                lastProgress = now()
                onProgress(manga, done, mangaList.size)
            }
            when (val outcome = update(manga, force)) {
                is Outcome.Updated -> updated++
                Outcome.Unchanged -> unchanged++
                Outcome.NoCovers -> noCovers++
                Outcome.NotTracked -> notTracked++
                Outcome.UserCover -> userCovers++
                is Outcome.Failed -> {
                    failed++
                    if (manga.id !in keepErrorsFor) writeError(manga, outcome.reason)
                }
                Outcome.CheckedRecently, Outcome.Skipped -> Unit
            }
        }
        if (force || updated > 0 || failed > 0) {
            val text = application.stringResource(
                KMR.strings.mangabaka_covers_result,
                updated,
                unchanged,
                noCovers,
                notTracked,
                userCovers,
                failed,
            )
            application.notify(NOTIFICATION_ID, Notifications.CHANNEL_LIBRARY_PROGRESS) {
                setSmallIcon(R.drawable.ic_komikku)
                setContentTitle(application.stringResource(KMR.strings.mangabaka_covers_result_title))
                setContentText(text)
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
                setAutoCancel(true)
            }
        }
        return failed
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
            Outcome.Failed(e.message ?: e::class.simpleName.orEmpty())
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
        data class Failed(val reason: String) : Outcome
        data object Skipped : Outcome
    }

    private suspend fun writeError(manga: Manga, reason: String) {
        val message = application.stringResource(KMR.strings.mangabaka_cover_failed, reason)
        val messageId = insertLibraryUpdateErrorMessages.insert(LibraryUpdateErrorMessage(-1L, message))
        insertLibraryUpdateErrors.upsert(LibraryUpdateError(id = -1L, mangaId = manga.id, messageId = messageId))
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

        val bytes = fetch(imageUrl) ?: throw HttpException(HTTP_NOT_FOUND)
        if (bytes.isEmpty()) return Outcome.Failed("empty image")
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
    private suspend fun getJson(url: String): JsonObject? =
        fetch(url)?.let { json.parseToJsonElement(it.decodeToString()).jsonObject }

    /**
     * Downloads [url], spaced so a whole-library pass stays under the API's 180 requests per minute.
     * Rate limits (HTTP 429, waiting as long as the server asks), server errors (5xx) and network errors
     * are retried up to [MAX_ATTEMPTS] times with growing waits, so a busy moment does not skip a title.
     * Returns null for HTTP 404 and throws for any other failure.
     */
    private suspend fun fetch(url: String): ByteArray? {
        var attempt = 1
        while (true) {
            delay(REQUEST_SPACING_MS)
            val wait = try {
                client.newCall(Request.Builder().url(url).build()).await().use { response ->
                    val code = response.code
                    when {
                        response.isSuccessful -> return response.body.bytes()
                        code == HTTP_NOT_FOUND -> return null
                        code != HTTP_TOO_MANY_REQUESTS && code < HTTP_SERVER_ERROR -> throw HttpException(code)
                        attempt >= MAX_ATTEMPTS -> throw HttpException(code)
                        else -> response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: backoff(attempt)
                    }
                }
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) throw e
                backoff(attempt)
            }
            delay(wait.coerceIn(RETRY_MIN_MS, RETRY_MAX_MS))
            attempt++
        }
    }

    /** 10 s, 20 s, 40 s, 80 s. */
    private fun backoff(attempt: Int) = RETRY_MIN_MS shl (attempt - 1)

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
        const val RECHECK_MS = 24L * 60 * 60 * 1000
        const val MAX_PAGES = 20
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        const val MAX_ATTEMPTS = 5
        const val RETRY_MIN_MS = 10_000L
        const val RETRY_MAX_MS = 120_000L
        const val REQUEST_SPACING_MS = 350L
        const val PROGRESS_INTERVAL_MS = 500L
        const val NOTIFICATION_ID = -110
    }
}
// KMK <--
