package eu.kanade.tachiyomi.data.cover

// KMK -->
import android.app.Application
import androidx.core.app.NotificationCompat
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.system.notify
import exh.log.xLogW
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
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
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
import kotlin.math.floor

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
    private val trackerManager: TrackerManager = Injekt.get(),
    private val application: Application = Injekt.get(),
    private val insertLibraryUpdateErrors: InsertLibraryUpdateErrors = Injekt.get(),
    private val insertLibraryUpdateErrorMessages: InsertLibraryUpdateErrorMessages = Injekt.get(),
) {

    private val client get() = networkHelper.client

    /** Tracker ID to MangaBaka source name, in order of preference. */
    private val sources = listOf(
        trackerManager.aniList.id to "anilist",
        trackerManager.myAnimeList.id to "my-anime-list",
        trackerManager.kitsu.id to "kitsu",
        trackerManager.mangaUpdates.id to "manga-updates",
    )
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
            showResult(
                KMR.strings.mangabaka_covers_result_title,
                application.stringResource(
                    KMR.strings.mangabaka_covers_result,
                    updated,
                    unchanged,
                    noCovers,
                    notTracked,
                    userCovers,
                    failed,
                ),
            )
        }
        return failed
    }

    /**
     * Puts the source's own cover back for every title whose cover this class wrote, for when the
     * feature is turned off. Covers the user set by hand stay. Markers are removed too, so turning the
     * feature back on checks every title afresh.
     */
    suspend fun restoreSourceCovers(
        mangaList: List<Manga>,
        onProgress: suspend (manga: Manga, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ) = withIOContext {
        var lastProgress = 0L
        var restored = 0
        mangaList.forEachIndexed { done, manga ->
            currentCoroutineContext().ensureActive()
            if (now() - lastProgress >= PROGRESS_INTERVAL_MS) {
                lastProgress = now()
                onProgress(manga, done, mangaList.size)
            }
            val coverFile = coverCache.getCustomCoverFile(manga.id)
            val markerFile = File(coverFile.parentFile, coverFile.name + ".mb")
            val marker = readMarker(markerFile) ?: return@forEachIndexed
            if (coverFile.exists() && marker.coverModified == coverFile.lastModified()) {
                coverCache.deleteCustomCover(manga.id)
                updateManga.awaitUpdateCoverLastModified(manga.id)
                restored++
            }
            markerFile.delete()
        }
        showResult(
            KMR.strings.mangabaka_covers_restored_title,
            application.stringResource(KMR.strings.mangabaka_covers_restored, restored),
        )
    }

    private fun showResult(title: StringResource, text: String) {
        application.notify(NOTIFICATION_ID, Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_komikku)
            setContentTitle(application.stringResource(title))
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setAutoCancel(true)
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
            xLogW("MangaBaka cover update failed for '${manga.title}'", e)
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
        val lastCheck = marker?.takeIf { it.language == language }?.checkedAt
        if (!force && lastCheck != null && now() - lastCheck < RECHECK_MS) return Outcome.CheckedRecently

        val tracks = getTracks.await(manga.id)
        val candidates = sources.mapNotNull { (trackerId, source) ->
            val remoteId = tracks.firstOrNull { it.trackerId == trackerId }?.remoteId?.takeIf { it > 0 }
                ?: return@mapNotNull null
            // MangaBaka identifies MangaUpdates series by the 7-character base-36 form of the numeric ID.
            val id = if (trackerId == trackerManager.mangaUpdates.id) {
                remoteId.toString(36).padStart(7, '0')
            } else {
                remoteId.toString()
            }
            source to id
        }
        if (candidates.isEmpty()) return Outcome.NotTracked

        // Try each linked tracker in order until MangaBaka knows the series.
        val seriesId = candidates.firstNotNullOfOrNull { (source, id) -> resolveSeriesId(source, id) }
        val latest = seriesId?.let { latestVolumeCover(it, language) }
        if (seriesId == null || latest == null) {
            // No covers in this language: go back to the source's cover, and remember the miss so it is
            // not looked up again on every update.
            if (coverFile.exists()) {
                coverCache.deleteCustomCover(manga.id)
                updateManga.awaitUpdateCoverLastModified(manga.id)
            }
            writeMarker(markerFile, Marker(0, "", now(), 0, language))
            return Outcome.NoCovers
        }
        val (index, imageUrl) = latest

        // The cover on disk is already this volume in this language.
        if (coverFile.exists() && marker != null && marker.isCover(seriesId, index, language)) {
            writeMarker(markerFile, marker.copy(checkedAt = now()))
            return Outcome.Unchanged
        }

        val bytes = fetch(imageUrl) ?: throw HttpException(HTTP_NOT_FOUND)
        if (bytes.isEmpty()) return Outcome.Failed("empty image")
        coverCache.setCustomCoverToCache(manga, bytes.inputStream())
        writeMarker(markerFile, Marker(seriesId, index, now(), coverFile.lastModified(), language))
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

    /** Latest volume with a cover in [language], as (volume label, image URL). */
    private suspend fun latestVolumeCover(seriesId: Long, language: String): Pair<String, String>? {
        var best: Triple<Double, String, String>? = null
        var page = 1
        while (page <= MAX_PAGES) {
            val root = getJson("$API/v1/series/$seriesId/images?type=volume&language=$language&limit=50&page=$page")
                ?: break
            val items = root["data"] as? JsonArray ?: break
            for (item in items.map { it.jsonObject }) {
                // Rank by the volume in the label: "3", or "3.1" for an alternate edition of volume 3, which
                // only counts when volume 3 itself has no cover. index_numeric is not used, as it does not
                // always follow the label. Labels that are not numbers ("Box set") are skipped.
                val label = item.str("index")?.takeIf { VOLUME_LABEL.matches(it) } ?: continue
                val number = label.toDouble()
                val rank = floor(number) * 2 + if (number == floor(number)) 1 else 0
                val image = item["image"]?.jsonObject ?: continue
                val url = image["x350"]?.jsonObject?.str("x2")
                    ?: image["raw"]?.jsonObject?.str("url")
                    ?: continue
                if (best == null || rank > best.first) {
                    best = Triple(rank, label, url)
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

    /** What this class last wrote for a title, in [language]. A miss (no covers) is stored with seriesId 0. */
    private data class Marker(
        val seriesId: Long,
        val index: String,
        val checkedAt: Long,
        val coverModified: Long,
        val language: String,
    ) {
        fun isCover(seriesId: Long, index: String, language: String) =
            this.seriesId == seriesId && this.index == index && this.language == language
    }

    private fun readMarker(file: File): Marker? = runCatching {
        val (seriesId, index, checkedAt, coverModified, language) = file.readText().trim().split('|')
        Marker(seriesId.toLong(), index, checkedAt.toLong(), coverModified.toLong(), language)
    }.getOrNull()

    private fun writeMarker(file: File, m: Marker) {
        file.writeText("${m.seriesId}|${m.index}|${m.checkedAt}|${m.coverModified}|${m.language}")
    }

    private companion object {
        const val API = "https://api.mangabaka.org"
        val VOLUME_LABEL = Regex("""\d+(\.\d+)?""")

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
