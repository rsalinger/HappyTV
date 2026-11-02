package com.rysal.happytv.ui.catalog

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.data.network.MovieDataDto
import com.rysal.happytv.data.network.VodInfoDto
import com.rysal.happytv.data.network.XtreamRepository
import com.rysal.happytv.ui.main.FakeXtreamApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [CatalogViewModel] — the shared Movies / Shows browse state.
 *
 * The persistence case is the one that matters most: long-pressing a poster
 * toggles the favorite, and that has to survive a process kill. This test
 * proves it with a **file-backed** Room database: toggle, close the database
 * (the stand-in for killing the app), reopen the same file, build a fresh
 * ViewModel, and read the favorite back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CatalogViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Application

    private var dbFile: File? = null
    private var db: HappyTvDatabase? = null
    private val viewModels = mutableListOf<CatalogViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Cancel AND JOIN the ViewModels' eager Room subscriptions before the
        // database goes away, exactly as a process death would — then close the
        // database and drain the main looper before Main is reset.
        //
        // Joining is the load-bearing part. `cancel()` alone only *requests*
        // cancellation: Room has already dispatched the flow's query onto its
        // own executor, and that statement runs to completion regardless. The
        // next line then deletes the database file out from under the in-flight
        // statement, and the query dies with
        // "no such table: vod_streams" — thrown on a Room executor thread, so
        // it surfaces as an `UncaughtExceptionsBeforeTest` against whatever
        // test the JUnit runner has *not yet started*, in an order unrelated to
        // the test that caused it. Room's late emissions land as queued looper
        // runnables that resume continuations on Main, so one of those racing
        // `resetMain()` trips the "Dispatchers.Main is used concurrently with
        // setting it" guard — a straggler's few microseconds of head start get a
        // retry, not a failure.
        runBlocking {
            viewModels.forEach { vm -> vm.viewModelScope.coroutineContext.job.cancelAndJoin() }
        }
        viewModels.clear()
        drainRoomExecutors()
        db?.close()
        db = null
        shadowOf(Looper.getMainLooper()).idle()
        var attempts = 0
        while (true) {
            try {
                Dispatchers.resetMain()
                break
            } catch (busy: IllegalStateException) {
                if (busy.message?.contains("concurrently") != true || ++attempts >= 50) throw busy
                Thread.sleep(10)
            }
        }
        dbFile?.delete()
        dbFile = null
    }

    /**
     * Barrier on Room's own executors: both are single-threaded, so a task
     * submitted here runs *after* every query the cancelled coroutines had
     * already queued — which is exactly the set that must finish before the
     * database file is deleted.
     *
     * Cancellation is not a barrier: Room dispatches a query to the query
     * executor, and cancelling the collecting coroutine does not interrupt a
     * statement already running on that thread. `long-press favorite survives
     * killing the app` exercises exactly that — it cancels the scope and closes
     * the database mid-test to simulate a process kill, and the next test to
     * run inherits whatever statement was still in flight.
     *
     * Bounded: a wedged executor must not hang the build.
     */
    private fun drainRoomExecutors() {
        val database = db ?: return
        listOf(database.queryExecutor, database.transactionExecutor).forEach { executor ->
            runCatching {
                val drained = java.util.concurrent.CountDownLatch(1)
                executor.execute { drained.countDown() }
                if (!drained.await(DB_DRAIN_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    android.util.Log.w(
                        "CatalogViewModelTest",
                        "Room executor did not drain within ${DB_DRAIN_GRACE_MS}ms",
                    )
                }
            }
        }
    }

    private fun openDb(): HappyTvDatabase {
        val file = dbFile ?: File(
            context.getDatabasePath("probe.db").parentFile ?: context.filesDir,
            "happytv-catalog-test.db",
        ).also {
            it.delete()
            dbFile = it
        }
        return Room.databaseBuilder(context, HappyTvDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .addMigrations(HappyTvDatabase.MIGRATION_1_2)
            .build()
            .also { db = it }
    }

    private suspend fun seedMovies(database: HappyTvDatabase) {
        database.categoryDao().replaceAll(
            StreamType.VOD,
            listOf(
                Category(id = "drama", type = StreamType.VOD, name = "Drama"),
                Category(id = "comedy", type = StreamType.VOD, name = "Comedy"),
            ),
        )
        database.vodStreamDao().replaceAll(
            listOf(
                VodStream(id = "m1", categoryId = "drama", num = 1, name = "The Long Coast", rating = 7.4),
                VodStream(id = "m2", categoryId = "drama", num = 2, name = "Copper Sky"),
                VodStream(id = "m3", categoryId = "comedy", num = 3, name = "Little Comets"),
            ),
        )
    }

    /** [count] movies in one category — "m0001"… per `num`, for the paging tests. */
    private suspend fun seedManyMovies(
        database: HappyTvDatabase,
        count: Int,
        categoryId: String = "drama",
    ) {
        database.categoryDao().replaceAll(
            StreamType.VOD,
            listOf(Category(id = categoryId, type = StreamType.VOD, name = "Drama")),
        )
        database.vodStreamDao().replaceAll(
            List(count) { index ->
                VodStream(
                    id = "m%04d".format(index + 1),
                    categoryId = categoryId,
                    num = index + 1,
                    name = "Movie %04d".format(index + 1),
                )
            },
        )
    }

    private fun newViewModel(
        database: HappyTvDatabase,
        categoryId: String? = null,
        streamId: String? = null,
        streamType: StreamType = StreamType.VOD,
        refreshProgress: kotlinx.coroutines.flow.Flow<com.rysal.happytv.data.network.RefreshState> =
            kotlinx.coroutines.flow.flow { emit(com.rysal.happytv.data.network.RefreshState.Idle) },
        session: suspend () -> XtreamRepository? = { null },
        lastCategory: kotlinx.coroutines.flow.Flow<String?>? = null,
        saveLastCategory: suspend (String) -> Unit = {},
    ) = CatalogViewModel(
        watchProgressDao = database.watchProgressDao(),
        categoryDao = database.categoryDao(),
        vodStreamDao = database.vodStreamDao(),
        seriesStreamDao = database.seriesStreamDao(),
        favoriteDao = database.favoriteDao(),
        streamType = streamType,
        categoryId = categoryId,
        streamId = streamId,
        refreshProgress = refreshProgress,
        session = session,
        lastCategory = lastCategory,
        saveLastCategory = saveLastCategory,
    ).also { viewModels += it }

    @Test
    fun `long-press favorite survives killing the app`() = runTest {
        val firstRun = openDb()
        seedMovies(firstRun)
        val viewModel = newViewModel(firstRun, streamId = "m1")
        advanceUntilIdle()

        assertFalse(
            "favorite starts off",
            viewModel.detail.first { it != null }!!.isFavorite,
        )

        viewModel.toggleFavorite("m1")
        val whileRunning = viewModel.detail.first { it!!.isFavorite }
        assertTrue(whileRunning!!.isFavorite)
        // The write reached the favorites table, not just the ViewModel.
        assertTrue(firstRun.favoriteDao().isFavorite("m1", StreamType.VOD))

        // Kill: cancel the ViewModel's scope and close the database, so what
        // is left on disk is all the next run gets.
        //
        // Join before closing, and drain Room's executors: cancelling only
        // *requests* cancellation, so a query already dispatched to Room's
        // query executor would otherwise still be running when the file is
        // closed — and the second run would inherit a statement dying with
        // "no such table: vod_streams".
        runBlocking { viewModel.viewModelScope.coroutineContext.job.cancelAndJoin() }
        listOf(firstRun.queryExecutor, firstRun.transactionExecutor).forEach { executor ->
            val drained = java.util.concurrent.CountDownLatch(1)
            executor.execute { drained.countDown() }
            drained.await(DB_DRAIN_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        firstRun.close()
        db = null

        // Restart: reopen the same file and read it back.
        val secondRun = openDb()
        assertTrue(
            "favorite must survive a process kill",
            secondRun.favoriteDao().isFavorite("m1", StreamType.VOD),
        )

        val restarted = newViewModel(secondRun, streamId = "m1")
        // Wait for the fresh run's own state to carry the favorite — the first
        // emission of a fresh ViewModel can still be the pre-write snapshot.
        val afterRestart = restarted.detail.first { it != null && it.isFavorite }
        assertNotNull("favorite must be visible to a restarted ViewModel", afterRestart)

        // And the poster grid shows the badge too.
        val grid = restarted.items.first { it.isNotEmpty() }
        assertTrue(grid.first { it.id == "m1" }.isFavorite)
    }

    @Test
    fun `poster grid lists one category and the All view spans them`() = runTest {
        val database = openDb()
        seedMovies(database)

        val drama = newViewModel(database, categoryId = "drama")
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), drama.items.first { it.isNotEmpty() }.map { it.id })

        val all = newViewModel(database, categoryId = CatalogViewModel.ALL_CATEGORY_ID)
        advanceUntilIdle()
        assertEquals(3, all.items.first { it.size == 3 }.size)
    }

    @Test
    fun `category counts feed the cells and the header total`() = runTest {
        val database = openDb()
        seedMovies(database)
        val viewModel = newViewModel(database)
        advanceUntilIdle()

        assertEquals(3, viewModel.totalItems.first { it == 3 })
        assertEquals(2, viewModel.categoryCounts.first { it["drama"] == 2 }["drama"])
        assertEquals(1, viewModel.categoryCounts.first { it["comedy"] == 1 }["comedy"])
        assertEquals(2, viewModel.categories.first { it.size == 2 }.size)
    }

    @Test
    fun `hidden categories leave the visible list until revealed`() = runTest {
        val database = openDb()
        seedMovies(database)
        val viewModel = newViewModel(database)
        advanceUntilIdle()

        viewModel.toggleCategoryHidden("comedy")
        // Wait for a loaded list: the StateFlow seeds with emptyList(), which
        // trivially satisfies "none is comedy" before Room has emitted.
        val hidden = viewModel.categories.first { categories ->
            categories.isNotEmpty() && categories.none { it.id == "comedy" }
        }
        assertEquals(listOf("drama"), hidden.map { it.id })

        // Unhiding is one category at a time now (Settings > Hidden items does the rest).
        viewModel.toggleCategoryHidden("comedy")
        val revealed = viewModel.categories.first { categories -> categories.any { it.id == "comedy" } }
        assertEquals(2, revealed.size)
    }

    @Test
    fun `favorites poster grid lists only starred items`() = runTest {
        val database = openDb()
        seedMovies(database)
        val viewModel = newViewModel(database, categoryId = CatalogViewModel.FAVORITES_CATEGORY_ID)
        advanceUntilIdle()

        assertTrue(viewModel.items.first().isEmpty())

        val grid = newViewModel(database, categoryId = CatalogViewModel.ALL_CATEGORY_ID)
        advanceUntilIdle()
        grid.toggleFavorite("m2")
        grid.items.first { items -> items.any { it.isFavorite } }

        val favorites = viewModel.items.first { it.isNotEmpty() }
        assertEquals(listOf("m2"), favorites.map { it.id })
    }

    @Test
    fun `show details is the v1 holding page with genres and plot parsed`() = runTest {
        val database = openDb()
        database.categoryDao().replaceAll(
            StreamType.SERIES,
            listOf(Category(id = "drama", type = StreamType.SERIES, name = "Drama")),
        )
        database.seriesStreamDao().replaceAll(
            listOf(
                SeriesStream(
                    id = "s1",
                    categoryId = "drama",
                    num = 1,
                    name = "Harbor Lights",
                    releaseDate = "2019-04-02",
                    genre = "Drama, Adventure",
                    plot = "A lighthouse keeper finds a boat.",
                    cast = "Ada Wren, Milo Fray",
                    rating = 8.1,
                ),
            ),
        )

        val viewModel = newViewModel(
            database,
            streamId = "s1",
            streamType = StreamType.SERIES,
        )
        advanceUntilIdle()

        val detail = viewModel.detail.first { it != null }!!
        assertEquals("Harbor Lights", detail.name)
        assertTrue(detail.isSeries)
        assertTrue("v1 shows the 'Play first episode' page", detail.hasEpisodes)
        assertEquals("2019", detail.year)
        assertEquals(listOf("Drama", "Adventure"), detail.genres)
        assertEquals("A lighthouse keeper finds a boat.", detail.plot)
        assertEquals(listOf("Ada Wren", "Milo Fray"), detail.cast)
    }

    @Test
    fun `a movie with no guide metadata falls back to the missing-info state`() = runTest {
        val database = openDb()
        seedMovies(database)
        val viewModel = newViewModel(database, streamId = "m2")
        advanceUntilIdle()

        val detail = viewModel.detail.first { it != null }!!
        assertFalse(detail.isSeries)
        assertEquals(null, detail.plot)
        assertTrue(detail.genres.isEmpty())
        assertTrue(detail.cast.isEmpty())
    }

    // ---------------------------------------------------------------- F-50
    //
    // Clicking a movie showed no detail: the parser was not reaching
    // `get_vod_info`'s `info` block (where plot/genre/cast/poster live).
    // `movie_data` only carries stream ids + container_extension, so a
    // parser pointed at it sees nulls on every detail field.

    /**
     * Real `get_vod_info` panel response shape (F-50 ground truth):
     * rich `info`, ids-only `movie_data`. `VodDetailParserTest` pins the
     * parser field-by-field against this shape; this test proves the
     * ViewModel actually surfaces those fields on the detail page.
     */
    private fun fixtureVodInfo() = Graph.json.decodeFromString<VodInfoDto>(
        """
        {
          "info": {
            "tmdb_id": "603",
            "movie_image": "https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
            "youtube_trailer": "vKQi3bBA1y8",
            "genre": "Action, Sci-Fi",
            "plot": "A computer hacker learns from mysterious rebels about the true nature of his reality.",
            "cast": "Keanu Reeves, Laurence Fishburne",
            "director": "Lana Wachowski, Lilly Wachowski",
            "rating": "8.7",
            "releasedate": "1999-03-31",
            "duration": "02:16:00",
            "age": "16+"
          },
          "movie_data": {
            "stream_id": "501",
            "name": "The Matrix",
            "category_id": "2",
            "container_extension": "mp4"
          }
        }
        """.trimIndent(),
    )

    private fun sessionWith(response: VodInfoDto): suspend () -> XtreamRepository? = {
        XtreamRepository(
            host = "panel.example",
            port = 8080,
            useTls = false,
            username = "u",
            password = "p",
            api = FakeXtreamApi().apply { vodInfoResponse = response },
        )
    }

    @Test
    fun `movie detail maps get_vod_info info block into plot, genre, cast, poster (F-50)`() = runTest {
        val database = openDb()
        seedMovies(database)
        val viewModel = newViewModel(
            database,
            streamId = "m1",
            session = sessionWith(fixtureVodInfo()),
        )
        advanceUntilIdle()

        val detail = viewModel.detail.first { it != null && it.plot != null }!!
        assertFalse(detail.isSeries)
        assertEquals(
            "plot ← info.plot",
            "A computer hacker learns from mysterious rebels about the true nature of his reality.",
            detail.plot,
        )
        assertEquals("genres ← info.genre (split + trimmed)", listOf("Action", "Sci-Fi"), detail.genres)
        assertEquals("cast ← info.cast (split + trimmed)", listOf("Keanu Reeves", "Laurence Fishburne"), detail.cast)
        assertEquals(
            "posterUrl ← info.movie_image (resolved through tmdb if needed)",
            "https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
            detail.posterUrl,
        )
        assertEquals("year ← releasedate.take(4)", "1999", detail.year)
        assertEquals("runtimeLabel ← duration (HH:MM:SS), spelled out", "2 h 16 min", detail.runtimeLabel)
        assertEquals("ageLabel ← age", "16+", detail.ageLabel)
        assertEquals("ratingLabel ← rating, labelled with its scale", "Rating 8.7/10", detail.ratingLabel)
    }

    @Test
    fun `movie detail falls back to missing-info when get_vod_info has no info block`() = runTest {
        val database = openDb()
        seedMovies(database)
        // Panel sent `movie_data` only — the exact shape F-50 filed.
        val viewModel = newViewModel(
            database,
            streamId = "m1",
            session = sessionWith(VodInfoDto(movie_data = MovieDataDto(stream_id = "501"))),
        )
        advanceUntilIdle()

        val detail = viewModel.detail.first { it != null }!!
        assertFalse(detail.isSeries)
        assertEquals("no info block → no plot", null, detail.plot)
        assertTrue("no info block → no genres", detail.genres.isEmpty())
        assertTrue("no info block → no cast", detail.cast.isEmpty())
    }

    @Test
    fun `movie detail survives a get_vod_info network failure without blanking the page`() = runTest {
        val database = openDb()
        seedMovies(database)
        val session: suspend () -> XtreamRepository? = {
            XtreamRepository(
                host = "panel.example",
                port = 8080,
                useTls = false,
                username = "u",
                password = "p",
                api = FakeXtreamApi().apply {
                    vodInfoFailure = java.io.IOException("panel unreachable")
                },
            )
        }
        val viewModel = newViewModel(database, streamId = "m1", session = session)
        advanceUntilIdle()

        // The row is still visible — the page degrades to the list-row fields
        // (name + rating) and the "missing info" copy, never crashes.
        val detail = viewModel.detail.first { it != null }!!
        assertEquals("m1", detail.id)
        assertEquals("The Long Coast", detail.name)
        assertEquals(null, detail.plot)
    }

    @Test
    fun `poster grid pages past the old 256 cap and the header keeps the real total`() = runTest {
        val database = openDb()
        // More than one page of rows, so the window boundary is real.
        val count = CatalogViewModel.PAGE_SIZE + 60
        seedManyMovies(database, count = count)
        val viewModel = newViewModel(database, categoryId = "drama")
        advanceUntilIdle()

        // F-55's wrong-data half: the header counts the category, not the
        // paged-in window.
        val headerTotal = viewModel.itemsTotal.first { it > 0 }
        assertEquals("the header shows the category's total", count, headerTotal)

        val firstPage = viewModel.items.first { it.isNotEmpty() }
        assertEquals("exactly one page is paged in at first", CatalogViewModel.PAGE_SIZE, firstPage.size)
        assertFalse("row 257 is not browsable yet", firstPage.any { it.id == "m0257" })

        // F-55's browse-limited half: the grid pages on past the old cap.
        viewModel.loadMorePosters()
        val paged = viewModel.items.first { it.size == count }
        assertEquals("m%04d".format(count), paged.last().id)
        assertTrue("the old cap boundary is browsable now", paged.any { it.id == "m0257" })
        assertEquals("the header still shows the real total", count, viewModel.itemsTotal.first { it == count })
    }

    @Test
    fun `paging stops at the last row instead of growing forever`() = runTest {
        val database = openDb()
        val count = CatalogViewModel.PAGE_SIZE + 10
        seedManyMovies(database, count = count)
        val viewModel = newViewModel(database, categoryId = "drama")
        advanceUntilIdle()

        assertEquals(CatalogViewModel.PAGE_SIZE, viewModel.items.first { it.isNotEmpty() }.size)
        viewModel.loadMorePosters()
        assertEquals("m%04d".format(count), viewModel.items.first { it.size == count }.last().id)

        // End of data: the window came back short, so nothing more is asked for.
        viewModel.loadMorePosters()
        advanceUntilIdle()
        assertEquals(count, viewModel.items.first { it.size == count }.size)
    }

    @Test
    fun `the All grid totals the catalog and an empty category loads as zero`() = runTest {
        val database = openDb()
        database.categoryDao().replaceAll(
            StreamType.VOD,
            listOf(
                Category(id = "drama", type = StreamType.VOD, name = "Drama"),
                Category(id = "empty", type = StreamType.VOD, name = "Empty"),
            ),
        )
        database.vodStreamDao().replaceAll(
            List(8) { index ->
                VodStream(
                    id = "m${index + 1}",
                    categoryId = "drama",
                    num = index + 1,
                    name = "Movie ${index + 1}",
                )
            },
        )

        val all = newViewModel(database, categoryId = CatalogViewModel.ALL_CATEGORY_ID)
        advanceUntilIdle()
        assertEquals(8, all.itemsTotal.first { it > 0 })
        assertEquals(8, all.items.first { it.size == 8 }.size)

        // An empty category is "loaded, zero rows" — not a spinner that never
        // resolves (the old `items.isNotEmpty()` load signal).
        val empty = newViewModel(database, categoryId = "empty")
        advanceUntilIdle()
        assertTrue("an empty category is loaded, not loading", empty.itemsLoaded.first { it })
        assertEquals(0, empty.itemsTotal.value)
        assertTrue(empty.items.value.isEmpty())
    }

    // ------------------------------------------------------------------ #68

    private suspend fun seedShow(
        database: HappyTvDatabase,
        cover: String?,
        backdropPath: String?,
    ) {
        database.categoryDao().replaceAll(
            StreamType.SERIES,
            listOf(Category(id = "drama", type = StreamType.SERIES, name = "Drama")),
        )
        database.seriesStreamDao().replaceAll(
            listOf(
                SeriesStream(
                    id = "s1",
                    categoryId = "drama",
                    name = "Agatha Raisin",
                    cover = cover,
                    backdropPath = backdropPath,
                ),
            ),
        )
    }

    private fun seriesSession(info: com.rysal.happytv.data.network.SeriesStreamDto?): suspend () -> XtreamRepository? = {
        XtreamRepository(
            host = "panel.example",
            port = 8080,
            useTls = false,
            username = "u",
            password = "p",
            api = FakeXtreamApi().apply {
                seriesInfoResponse = com.rysal.happytv.data.network.SeriesInfoDto(info = info)
            },
        )
    }

    @Test
    fun `show details carry the grid card's cover and the stored backdrop without a session (68)`() = runTest {
        val database = openDb()
        seedShow(database, cover = "https://img.example/cover.jpg", backdropPath = "[\"https://img.example/back.jpg\"]")
        val viewModel = newViewModel(database, streamId = "s1", streamType = StreamType.SERIES)
        advanceUntilIdle()

        val detail = viewModel.detail.first { it != null }!!
        val card = database.seriesStreamDao().getById("s1")!!.posterUrl
        assertEquals("details poster == grid card poster", card, detail.posterUrl)
        assertEquals("https://img.example/cover.jpg", detail.posterUrl)
        assertEquals("https://img.example/back.jpg", detail.backdropUrl)
    }

    @Test
    fun `show details take the backdrop from get_series_info as an array`() = runTest {
        val database = openDb()
        seedShow(database, cover = "https://img.example/cover.jpg", backdropPath = "[]")
        val info = com.rysal.happytv.data.network.SeriesStreamDto(
            series_id = "s1",
            cover = "https://img.example/other.jpg",
            backdrop_path = kotlinx.serialization.json.JsonArray(
                listOf(kotlinx.serialization.json.JsonPrimitive("https://img.example/info-back.jpg")),
            ),
        )
        val viewModel = newViewModel(database, streamId = "s1", streamType = StreamType.SERIES, session = seriesSession(info))
        advanceUntilIdle()

        val detail = viewModel.detail.first { it?.backdropUrl != null }!!
        assertEquals("https://img.example/info-back.jpg", detail.backdropUrl)
        assertEquals("the card's cover still wins", "https://img.example/cover.jpg", detail.posterUrl)
    }

    @Test
    fun `show details take the backdrop from get_series_info as a plain string`() = runTest {
        val database = openDb()
        seedShow(database, cover = "https://img.example/cover.jpg", backdropPath = null)
        val info = com.rysal.happytv.data.network.SeriesStreamDto(
            series_id = "s1",
            backdrop_path = kotlinx.serialization.json.JsonPrimitive("https://img.example/str-back.jpg"),
        )
        val viewModel = newViewModel(database, streamId = "s1", streamType = StreamType.SERIES, session = seriesSession(info))
        advanceUntilIdle()

        val detail = viewModel.detail.first { it?.backdropUrl != null }!!
        assertEquals("https://img.example/str-back.jpg", detail.backdropUrl)
    }

    @Test
    fun `show details fall back to get_series_info cover when the grid row has none`() = runTest {
        val database = openDb()
        seedShow(database, cover = null, backdropPath = null)
        val info = com.rysal.happytv.data.network.SeriesStreamDto(
            series_id = "s1",
            cover = "https://img.example/info-cover.jpg",
            backdrop_path = kotlinx.serialization.json.JsonArray(emptyList()),
        )
        val viewModel = newViewModel(database, streamId = "s1", streamType = StreamType.SERIES, session = seriesSession(info))
        advanceUntilIdle()

        val detail = viewModel.detail.first { it?.posterUrl != null }!!
        assertEquals("https://img.example/info-cover.jpg", detail.posterUrl)
        assertEquals("empty backdrop array stays null", null, detail.backdropUrl)
    }

    @Test
    fun `show details survive a get_series_info failure and keep the row art`() = runTest {
        val database = openDb()
        seedShow(database, cover = "https://img.example/cover.jpg", backdropPath = null)
        val session: suspend () -> XtreamRepository? = {
            XtreamRepository(
                host = "panel.example",
                port = 8080,
                useTls = false,
                username = "u",
                password = "p",
                api = FakeXtreamApi().apply { seriesInfoFailure = java.io.IOException("down") },
            )
        }
        val viewModel = newViewModel(database, streamId = "s1", streamType = StreamType.SERIES, session = session)
        advanceUntilIdle()

        val detail = viewModel.detail.first { it != null }!!
        assertEquals("https://img.example/cover.jpg", detail.posterUrl)
    }

    // ------------------------------------------------------------------ F-60

    @Test
    fun `categoriesLoaded is false until the catalog refreshes, then it latches`() = runTest {
        // F-60: on cold start the categories screen must not flash "0 movies
        // · 0 categories" over an empty grid. The gate is false while the
        // type's refresh is `Idle` or `Loading`; it flips to true once the
        // refresh has settled (Done or Error). After the latch flips, even a
        // refresh-wipe keeps the empty state visible instead of an endless
        // skeleton.
        val database = openDb()
        val viewModel = newViewModel(database)
        advanceUntilIdle()

        assertFalse(
            "before any refresh, the cold-start loading gate must be false (no flash)",
            viewModel.categoriesLoaded.value,
        )

        // Simulate the refresh completing with content: Idle → Loading → Done.
        database.categoryDao().replaceAll(
            StreamType.VOD,
            listOf(
                Category(id = "drama", type = StreamType.VOD, name = "Drama"),
            ),
        )
        val viewModelAfter = newViewModel(
            database,
            refreshProgress = kotlinx.coroutines.flow.flow {
                emit(com.rysal.happytv.data.network.RefreshState.Idle)
                kotlinx.coroutines.delay(10)
                emit(com.rysal.happytv.data.network.RefreshState.Loading(step = 1, ofSteps = 3))
                kotlinx.coroutines.delay(10)
                emit(com.rysal.happytv.data.network.RefreshState.Done(channels = 1, ms = 10))
            },
        )
        advanceUntilIdle()
        val afterDone = viewModelAfter.categoriesLoaded.first { it }
        assertTrue(
            "the gate must latch once the refresh settles",
            afterDone,
        )

        // Latch survives a refresh-wipe: no endless skeleton afterward.
        database.categoryDao().replaceAll(StreamType.VOD, emptyList())
        advanceUntilIdle()
        assertTrue(
            "an empty-after-loaded catalog must not flip the gate back",
            viewModelAfter.categoriesLoaded.value,
        )
    }

    // ------------------------------------------------------------------ F-64

    @Test
    fun `categoriesLoaded latches on an empty-Done refresh instead of staying on Loading forever`() = runTest {
        // F-64: a panel that returns `[]` for `get_vod_categories` (no VOD)
        // used to leave the Movies/Shows header on "Loading…" forever,
        // because the F-60 latch required a non-empty emission from the
        // categories flow. The new rule is to latch on the RefreshState —
        // a `Done` (or `Error`) flips the gate, even when the catalog is
        // empty. A `Loading` keeps the gate false so there is no flash of
        // "0 movies · 0 categories" while a load is in flight.
        val database = openDb()
        // Mid-refresh: the network call answered and wrote an empty list,
        // but the gate must still be holding until `Done` arrives.
        val viewModel = newViewModel(
            database,
            refreshProgress = kotlinx.coroutines.flow.flow {
                emit(com.rysal.happytv.data.network.RefreshState.Idle)
                kotlinx.coroutines.delay(10)
                emit(com.rysal.happytv.data.network.RefreshState.Loading(step = 1, ofSteps = 3))
                kotlinx.coroutines.delay(10)
                emit(com.rysal.happytv.data.network.RefreshState.Loading(step = 2, ofSteps = 3))
                kotlinx.coroutines.delay(10)
                emit(com.rysal.happytv.data.network.RefreshState.Loading(step = 3, ofSteps = 3))
            },
        )
        advanceUntilIdle()

        assertFalse(
            "while the refresh is still Loading, the gate must be false " +
                "(no flash of '0 movies · 0 categories' during a real load)",
            viewModel.categoriesLoaded.value,
        )

        // The "no VOD" refresh completes with 0 rows: gate must now flip,
        // and the user-visible empty state has to paint.
        val viewModelAfter = newViewModel(
            database,
            refreshProgress = kotlinx.coroutines.flow.flow {
                emit(com.rysal.happytv.data.network.RefreshState.Idle)
                kotlinx.coroutines.delay(10)
                emit(com.rysal.happytv.data.network.RefreshState.Done(channels = 0, ms = 30))
            },
        )
        advanceUntilIdle()

        assertTrue(
            "the gate must latch on a Done refresh with 0 rows — that's " +
                "the F-64 fix; the screen must reach the empty state instead " +
                "of staying on Loading… forever",
            viewModelAfter.categoriesLoaded.value,
        )
        assertEquals(0, viewModelAfter.totalItems.value)
    }

    // ------------------------------------------------------------------ F-64
    // Error half: a refresh that errored is also "settled", so the gate
    // latches — the user sees the empty/error state instead of an endless
    // skeleton.

    @Test
    fun `categoriesLoaded latches on an Error refresh so an unreachable panel does not freeze the screen`() = runTest {
        val viewModel = newViewModel(
            openDb(),
            refreshProgress = kotlinx.coroutines.flow.flow {
                emit(com.rysal.happytv.data.network.RefreshState.Idle)
                kotlinx.coroutines.delay(10)
                emit(com.rysal.happytv.data.network.RefreshState.Error("panel unreachable"))
            },
        )
        advanceUntilIdle()

        assertTrue(
            "an Error refresh counts as settled — the screen must not stay on Loading…",
            viewModel.categoriesLoaded.value,
        )
    }

    private companion object {
        /**
         * Grace period for the Room executor barrier in [drainRoomExecutors].
         * Long enough that a busy executor always drains in practice, short
         * enough that a genuine regression costs one test's time rather than
         * the whole run's.
         */
        private const val DB_DRAIN_GRACE_MS = 5_000L
    }

    // ------------------------------------------------------------ one screen, many categories (#72)

    @Test
    fun `selecting a category swaps the grid and is loaded only once its rows land`() = runTest {
        val database = openDb()
        seedMovies(database)
        val viewModel = newViewModel(database, categoryId = "drama")
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), viewModel.grid.first { it.categoryId == "drama" && it.loaded }.items.map { it.id })

        viewModel.selectCategory("comedy")
        val comedy = viewModel.grid.first { it.categoryId == "comedy" && it.loaded }
        assertEquals(listOf("m3"), comedy.items.map { it.id })
        assertEquals("comedy", viewModel.selectedCategory.value)
        assertEquals("the header count follows the selection", 1, viewModel.itemsTotal.first { it == 1 })
    }

    @Test
    fun `a grid is never loaded under a selection its rows do not belong to`() = runTest {
        val database = openDb()
        seedMovies(database)
        val viewModel = newViewModel(database, categoryId = "drama")
        advanceUntilIdle()
        viewModel.grid.first { it.categoryId == "drama" && it.loaded }

        viewModel.selectCategory("comedy")
        val states = mutableListOf<CatalogGrid>()
        // One collector both records and decides when to stop. A second, separate collector
        // (recording on its own coroutine) can be cancelled before it has seen the final value:
        // Room emits on its own thread, the waiting test resumes first, and `job.cancel()` then
        // drops the last state (#85). Recording inside the waiter makes the last entry the one
        // that satisfied the wait, whatever the timing.
        viewModel.grid
            .onEach { states += it }
            .first { it.categoryId == "comedy" && it.loaded }
        // Whatever the timing, rows are only ever reported under the category they belong to.
        states.forEach { state ->
            when (state.categoryId) {
                "drama" -> assertEquals(listOf("m1", "m2"), state.items.map { it.id })
                "comedy" -> assertEquals(listOf("m3"), state.items.map { it.id })
            }
        }
        val last = states.last()
        assertEquals("comedy", last.categoryId)
        assertTrue(last.loaded)
    }

    @Test
    fun `Recently added lists the newest first, leaves out unstamped rows, and Pick shows nothing`() = runTest {
        val database = openDb()
        database.categoryDao().replaceAll(
            StreamType.VOD,
            listOf(Category(id = "drama", type = StreamType.VOD, name = "Drama")),
        )
        database.vodStreamDao().replaceAll(
            listOf(
                VodStream(id = "old", categoryId = "drama", num = 1, name = "Old", added = 100L),
                VodStream(id = "undated", categoryId = "drama", num = 2, name = "Undated"),
                VodStream(id = "new", categoryId = "drama", num = 3, name = "New", added = 900L),
            ),
        )
        val viewModel = newViewModel(database, categoryId = CatalogViewModel.RECENT_CATEGORY_ID)
        advanceUntilIdle()
        // Rows with no stamp say nothing about when they arrived: they are left out.
        assertEquals(listOf("new", "old"), viewModel.grid.first { it.loaded && it.items.size == 2 }.items.map { it.id })

        viewModel.selectCategory(CatalogViewModel.PICK_CATEGORY_ID)
        val none = viewModel.grid.first { it.categoryId == CatalogViewModel.PICK_CATEGORY_ID && it.loaded }
        assertTrue(none.items.isEmpty())
    }

    @Test
    fun `Recently added is the newest few hundred in one page`() = runTest {
        val database = openDb()
        database.categoryDao().replaceAll(
            StreamType.VOD,
            listOf(Category(id = "drama", type = StreamType.VOD, name = "Drama")),
        )
        val count = CatalogViewModel.RECENT_CAP + 50
        database.vodStreamDao().replaceAll(
            List(count) { i ->
                VodStream(id = "m%04d".format(i), categoryId = "drama", num = i, name = "Movie $i", added = 1_000L + i)
            },
        )
        val viewModel = newViewModel(database, categoryId = CatalogViewModel.RECENT_CATEGORY_ID)
        advanceUntilIdle()
        val page = viewModel.grid.first { it.loaded && it.items.size == CatalogViewModel.RECENT_CAP }.items
        assertEquals(CatalogViewModel.RECENT_CAP, page.size)
        assertEquals("the newest first", "m%04d".format(count - 1), page.first().id)
        assertEquals("m%04d".format(count - CatalogViewModel.RECENT_CAP), page.last().id)
        // Nothing more to page in.
        viewModel.loadMorePosters()
        advanceUntilIdle()
        // `items` is its own Eagerly stateIn over `grid`: reading `.value` right after waiting on
        // `grid` can still see its empty seed (#91). Wait on the flow being asserted.
        assertEquals(CatalogViewModel.RECENT_CAP, viewModel.items.first { it.size == CatalogViewModel.RECENT_CAP }.size)
    }

    @Test
    fun `the screen opens on the remembered category and saves each pick`() = runTest {
        val database = openDb()
        seedMovies(database)
        val saved = mutableListOf<String>()
        val viewModel = newViewModel(
            database,
            lastCategory = kotlinx.coroutines.flow.flowOf("comedy"),
            saveLastCategory = { saved += it },
        )
        advanceUntilIdle()
        assertEquals("comedy", viewModel.selectedCategory.first { it == "comedy" })
        assertFalse(viewModel.restoringSelection.value)
        assertTrue("opening is not a pick", saved.isEmpty())

        viewModel.selectCategory("drama")
        advanceUntilIdle()
        assertEquals(listOf("drama"), saved)
    }

    @Test
    fun `a first visit with no stamps anywhere opens on the whole catalog`() = runTest {
        val database = openDb()
        seedMovies(database)
        val viewModel = newViewModel(database, lastCategory = kotlinx.coroutines.flow.flowOf(null))
        advanceUntilIdle()
        viewModel.restoringSelection.first { !it }
        assertEquals(CatalogViewModel.ALL_CATEGORY_ID, viewModel.selectedCategory.value)
    }

    @Test
    fun `a first visit opens on Favorites when it has items, else Recently added`() = runTest {
        val database = openDb()
        seedMovies(database)
        database.vodStreamDao().insertAll(listOf(VodStream(id = "m9", categoryId = "drama", num = 9, name = "Stamped", added = 5L)))
        val fresh = newViewModel(database, lastCategory = kotlinx.coroutines.flow.flowOf(null))
        advanceUntilIdle()
        assertEquals(CatalogViewModel.RECENT_CATEGORY_ID, fresh.selectedCategory.first { it != CatalogViewModel.ALL_CATEGORY_ID })

        database.favoriteDao().toggle("m1", StreamType.VOD)
        val withStar = newViewModel(database, lastCategory = kotlinx.coroutines.flow.flowOf(null))
        advanceUntilIdle()
        assertEquals(CatalogViewModel.FAVORITES_CATEGORY_ID, withStar.selectedCategory.first { it != CatalogViewModel.ALL_CATEGORY_ID })
    }

    @Test
    fun `the remembered category falls back when it has gone`() {
        val visible = listOf("drama", "comedy")
        assertEquals("drama", CatalogViewModel.initialCategoryId("drama", visible, hasFavorites = false))
        assertEquals(
            CatalogViewModel.RECENT_CATEGORY_ID,
            CatalogViewModel.initialCategoryId("removed", visible, hasFavorites = false),
        )
        assertEquals(
            CatalogViewModel.FAVORITES_CATEGORY_ID,
            CatalogViewModel.initialCategoryId("removed", visible, hasFavorites = true),
        )
        // Favorites with nothing in it is not somewhere to open.
        assertEquals(
            CatalogViewModel.RECENT_CATEGORY_ID,
            CatalogViewModel.initialCategoryId(CatalogViewModel.FAVORITES_CATEGORY_ID, visible, hasFavorites = false),
        )
        assertEquals(
            CatalogViewModel.ALL_CATEGORY_ID,
            CatalogViewModel.initialCategoryId(CatalogViewModel.ALL_CATEGORY_ID, visible, hasFavorites = false),
        )
    }

    @Test
    fun `the details title carries the year once`() = runTest {
        val database = openDb()
        database.categoryDao().replaceAll(
            StreamType.SERIES,
            listOf(Category(id = "c", type = StreamType.SERIES, name = "Drama")),
        )
        database.seriesStreamDao().replaceAll(
            listOf(SeriesStream(id = "s1", categoryId = "c", name = "Dark Matter (2015)", releaseDate = "2015-05-12", rating = 3.8)),
        )
        val viewModel = newViewModel(database, streamId = "s1", streamType = StreamType.SERIES)
        advanceUntilIdle()
        val detail = viewModel.detail.first { it != null }!!
        assertEquals("Dark Matter", detail.name)
        assertEquals("2015", detail.year)
        assertEquals("Rating 3.8/10", detail.ratingLabel)
    }

    @Test
    fun `a title that ends in its year shows the year once on the grid`() = runTest {
        val database = openDb()
        database.categoryDao().replaceAll(
            StreamType.SERIES,
            listOf(Category(id = "c", type = StreamType.SERIES, name = "Anime")),
        )
        database.seriesStreamDao().replaceAll(
            listOf(
                SeriesStream(id = "s1", categoryId = "c", num = 1, name = "Bleach (2004)", releaseDate = "2004-10-05"),
                SeriesStream(id = "s2", categoryId = "c", num = 2, name = "Dune (1984)", releaseDate = "2021-01-01"),
                SeriesStream(id = "s3", categoryId = "c", num = 3, name = "Plain title", releaseDate = "2010-01-01"),
            ),
        )
        val viewModel = newViewModel(
            database, categoryId = CatalogViewModel.ALL_CATEGORY_ID, streamType = StreamType.SERIES,
        )
        advanceUntilIdle()
        val items = viewModel.grid.first { it.loaded && it.items.size == 3 }.items.associateBy { it.id }
        assertEquals("Bleach" to "2004", items.getValue("s1").name to items.getValue("s1").year)
        // A different year in the data is a different fact: the title keeps its own.
        assertEquals("Dune (1984)" to "2021", items.getValue("s2").name to items.getValue("s2").year)
        assertEquals("Plain title" to "2010", items.getValue("s3").name to items.getValue("s3").year)
    }

    @Test
    fun `a movie title that ends in a year moves it to the year line`() = runTest {
        val database = openDb()
        database.categoryDao().replaceAll(
            StreamType.VOD,
            listOf(Category(id = "d", type = StreamType.VOD, name = "Drama")),
        )
        database.vodStreamDao().replaceAll(
            listOf(
                VodStream(id = "m1", categoryId = "d", num = 1, name = "15 Minutes (2001)"),
                VodStream(id = "m2", categoryId = "d", num = 2, name = "1917"),
            ),
        )
        val viewModel = newViewModel(database, categoryId = CatalogViewModel.ALL_CATEGORY_ID)
        advanceUntilIdle()
        val items = viewModel.grid.first { it.loaded && it.items.size == 2 }.items.associateBy { it.id }
        assertEquals("15 Minutes" to "2001", items.getValue("m1").name to items.getValue("m1").year)
        assertEquals("1917" to null, items.getValue("m2").name to items.getValue("m2").year)
    }

    // ------------------------------------------------------------------ watch progress (#78)

    private suspend fun seedShowRow(database: HappyTvDatabase) {
        database.categoryDao().replaceAll(
            StreamType.SERIES,
            listOf(Category(id = "drama", type = StreamType.SERIES, name = "Drama")),
        )
        database.seriesStreamDao().replaceAll(
            listOf(SeriesStream(id = "s1", categoryId = "drama", name = "Show one", cover = "https://img.example/s1.jpg")),
        )
    }

    @Test
    fun `Continue watching lists movies in progress newest first with a progress fraction`() = runTest {
        val database = openDb()
        database.vodStreamDao().replaceAll(
            listOf(
                VodStream(id = "a", categoryId = "drama", name = "Alpha"),
                VodStream(id = "b", categoryId = "drama", name = "Beta"),
            ),
        )
        database.watchProgressDao().upsert(WatchProgress(StreamType.VOD, "a", null, null, null, 1_800_000L, 7_200_000L, 10L))
        database.watchProgressDao().upsert(WatchProgress(StreamType.VOD, "b", null, null, null, 3_600_000L, 7_200_000L, 20L))
        val viewModel = newViewModel(database, categoryId = CatalogViewModel.CONTINUE_CATEGORY_ID)
        advanceUntilIdle()

        val grid = viewModel.grid.first { it.categoryId == CatalogViewModel.CONTINUE_CATEGORY_ID && it.items.size == 2 }
        assertEquals(listOf("b", "a"), grid.items.map { it.id })
        assertEquals(0.5f, grid.items[0].progressFraction!!, 0.001f)
        assertEquals(0.25f, grid.items[1].progressFraction!!, 0.001f)
        assertEquals(2, viewModel.continueCount.first { it == 2 })
    }

    @Test
    fun `Continue watching shows one card per show with its episode label`() = runTest {
        val database = openDb()
        seedShowRow(database)
        database.watchProgressDao().upsert(WatchProgress(StreamType.SERIES, "e1", "s1", 1, 1, 600_000L, 2_400_000L, 10L))
        database.watchProgressDao().upsert(WatchProgress(StreamType.SERIES, "e2", "s1", 2, 3, 900_000L, 2_400_000L, 20L))
        val viewModel = newViewModel(database, streamType = StreamType.SERIES, categoryId = CatalogViewModel.CONTINUE_CATEGORY_ID)
        advanceUntilIdle()

        val grid = viewModel.grid.first { it.categoryId == CatalogViewModel.CONTINUE_CATEGORY_ID && it.items.isNotEmpty() }
        assertEquals(listOf("s1"), grid.items.map { it.id })
        assertEquals("S2 E3", grid.items.single().episodeLabel)
        assertEquals(0.375f, grid.items.single().progressFraction!!, 0.001f)
    }

    @Test
    fun `removing a movie from Continue watching forgets it, and the emptied row is left`() = runTest {
        val database = openDb()
        database.vodStreamDao().replaceAll(listOf(VodStream(id = "a", categoryId = "drama", name = "Alpha")))
        database.watchProgressDao().upsert(WatchProgress(StreamType.VOD, "a", null, null, null, 1_800_000L, 7_200_000L, 10L))
        val viewModel = newViewModel(database, categoryId = CatalogViewModel.CONTINUE_CATEGORY_ID)
        advanceUntilIdle()
        viewModel.continueCount.first { it == 1 }

        viewModel.removeFromContinue("a")
        advanceUntilIdle()

        assertEquals(0, viewModel.continueCount.first { it == 0 })
        assertEquals(null, database.watchProgressDao().getMovie("a"))
        // Nothing left to continue: the screen moves to the whole catalog.
        assertEquals(CatalogViewModel.ALL_CATEGORY_ID, viewModel.selectedCategory.first { it != CatalogViewModel.CONTINUE_CATEGORY_ID })
    }

    @Test
    fun `removing a show from Continue watching forgets every episode of it`() = runTest {
        val database = openDb()
        seedShowRow(database)
        database.watchProgressDao().upsert(WatchProgress(StreamType.SERIES, "e1", "s1", 1, 1, 600_000L, 2_400_000L, 10L))
        database.watchProgressDao().upsert(WatchProgress(StreamType.SERIES, "e2", "s1", 1, 2, 600_000L, 2_400_000L, 20L))
        val viewModel = newViewModel(database, streamType = StreamType.SERIES)
        advanceUntilIdle()

        viewModel.removeFromContinue("s1")
        advanceUntilIdle()

        // The delete runs on Room's executor: wait on the table, not on the test scheduler.
        assertEquals(emptyList<WatchProgress>(), database.watchProgressDao().observeEpisodes("s1").first { it.isEmpty() })
    }

    @Test
    fun `movie details offer a resume point once progress exists`() = runTest {
        val database = openDb()
        database.vodStreamDao().replaceAll(listOf(VodStream(id = "a", categoryId = "drama", name = "Alpha")))
        val viewModel = newViewModel(database, streamId = "a")
        advanceUntilIdle()
        assertEquals(null, viewModel.detail.first { it != null }!!.resume)

        database.watchProgressDao().upsert(WatchProgress(StreamType.VOD, "a", null, null, null, 2_480_000L, 7_200_000L, 10L))
        advanceUntilIdle()
        val resume = viewModel.detail.first { it?.resume != null }!!.resume!!
        assertEquals(2_480_000L, resume.positionMillis)
        assertEquals("Resume from 41:20", resume.buttonLabel)
        assertEquals(null, resume.episodeId)
    }

    @Test
    fun `show details resume at the latest episode in progress`() = runTest {
        val database = openDb()
        seedShowRow(database)
        database.watchProgressDao().upsert(WatchProgress(StreamType.SERIES, "e1", "s1", 1, 1, 600_000L, 2_400_000L, 10L))
        database.watchProgressDao().upsert(WatchProgress(StreamType.SERIES, "e7", "s1", 2, 3, 1_200_000L, 2_400_000L, 20L))
        val viewModel = newViewModel(database, streamType = StreamType.SERIES, streamId = "s1")
        advanceUntilIdle()

        val resume = viewModel.detail.first { it?.resume != null }!!.resume!!
        assertEquals("e7", resume.episodeId)
        assertEquals("S2 E3", resume.label)
        assertEquals(1_200_000L, resume.positionMillis)
    }

    @Test
    fun `an up next episode shows as a card with its label and no progress bar`() = runTest {
        val database = openDb()
        seedShowRow(database)
        database.watchProgressDao().upsert(WatchProgress(StreamType.SERIES, "e1", "s1", 2, 3, 2_300_000L, 2_400_000L, 10L, finishedAt = 10L))
        database.watchProgressDao().upsert(WatchProgress(StreamType.SERIES, "e2", "s1", 2, 4, 0L, 0L, 11L))
        val viewModel = newViewModel(database, streamType = StreamType.SERIES, categoryId = CatalogViewModel.CONTINUE_CATEGORY_ID)
        advanceUntilIdle()

        val card = viewModel.grid.first { it.categoryId == CatalogViewModel.CONTINUE_CATEGORY_ID && it.items.isNotEmpty() }.items.single()
        assertEquals("S2 E4 \u00b7 Up next", card.episodeLabel)
        assertEquals(null, card.progressFraction)
    }

    @Test
    fun `details of a finished movie offer plain Play`() = runTest {
        val database = openDb()
        database.vodStreamDao().replaceAll(listOf(VodStream(id = "a", categoryId = "drama", name = "Alpha")))
        database.watchProgressDao().upsert(WatchProgress(StreamType.VOD, "a", null, null, null, 7_000_000L, 7_200_000L, 10L, finishedAt = 10L))
        val viewModel = newViewModel(database, streamId = "a")
        advanceUntilIdle()
        assertEquals(null, viewModel.detail.first { it != null }!!.resume)
    }

    @Test
    fun `an up next episode offers Play with its label, and Start over restarts from zero`() {
        val upNext = ResumePoint(0L, "e2", "S2 E4")
        assertTrue(upNext.isUpNext)
        assertEquals("Play S2 E4", upNext.buttonLabel)
        val resume = ResumePoint(1_200_000L, "e7", "S2 E3")
        assertEquals(false, resume.isUpNext)
        val over = resume.asStartOver()
        assertEquals(0L, over.positionMillis)
        assertEquals("e7", over.episodeId)
        assertTrue(over.startOver)
    }

    // ---------------------------------------------------------------- #74 episodes

    private val episodesJson = """
        {"1":[{"id":"e2","episode_num":"2","title":"Second","container_extension":"mp4","info":{"duration":"00:42:10","plot":"Plot two"}},
              {"id":"e1","episode_num":1,"title":"First","container_extension":"mp4","info":{"duration_secs":"1500"}}],
         "2":[{"id":"e3","episode_num":"1","title":"Return","container_extension":"mkv"}],
         "0":[{"id":"sp1","episode_num":"1","title":"Behind the scenes"}]}
    """.trimIndent()

    private fun episodesApi(json: String?): FakeXtreamApi = FakeXtreamApi().apply {
        if (json != null) {
            seriesInfoResponse = com.rysal.happytv.data.network.SeriesInfoDto(
                info = com.rysal.happytv.data.network.SeriesStreamDto(name = "Show one", series_id = "s1"),
                episodes = Graph.json.parseToJsonElement(json),
            )
        }
    }

    private fun sessionOf(api: FakeXtreamApi): suspend () -> XtreamRepository? = {
        XtreamRepository(host = "panel.example", port = 8080, useTls = false, username = "u", password = "p", api = api)
    }

    @Test
    fun `episodes are keyed by season, string keys included, with Specials last and no season metadata`() = runTest {
        val database = openDb()
        seedShowRow(database)
        val viewModel = newViewModel(
            database, streamType = StreamType.SERIES, streamId = "s1",
            session = sessionOf(episodesApi(episodesJson)),
        )
        advanceUntilIdle()

        val loaded = viewModel.episodes.first { it is EpisodesState.Loaded } as EpisodesState.Loaded
        assertEquals(listOf("Season 1", "Season 2", "Specials"), loaded.seasons.map { it.label })
        // Ordered by episode number, whatever order the provider sent.
        assertEquals(listOf("e1", "e2"), loaded.seasons[0].episodes.map { it.id })
        assertEquals("E2 · Second", loaded.seasons[0].episodes[1].heading)
        assertEquals("42 min", loaded.seasons[0].episodes[1].runtimeLabel)
        assertEquals("25 min", loaded.seasons[0].episodes[0].runtimeLabel)
    }

    @Test
    fun `with no saved progress the primary action is Play S1 E1 and Specials never come first`() = runTest {
        val database = openDb()
        seedShowRow(database)
        val viewModel = newViewModel(
            database, streamType = StreamType.SERIES, streamId = "s1",
            session = sessionOf(episodesApi(episodesJson)),
        )
        advanceUntilIdle()

        val resume = viewModel.detail.first { it?.resume != null }!!.resume!!
        assertEquals("Play S1 E1", resume.buttonLabel)
        assertEquals("e1", resume.episodeId)
    }

    @Test
    fun `a saved episode wins the primary action and reads Resume S2 E3`() = runTest {
        val database = openDb()
        seedShowRow(database)
        database.watchProgressDao().upsert(WatchProgress(StreamType.SERIES, "e3", "s1", 2, 3, 1_200_000L, 2_400_000L, 20L))
        val viewModel = newViewModel(
            database, streamType = StreamType.SERIES, streamId = "s1",
            session = sessionOf(episodesApi(episodesJson)),
        )
        advanceUntilIdle()
        viewModel.episodes.first { it is EpisodesState.Loaded }

        val resume = viewModel.detail.first { it?.resume?.episodeId == "e3" }!!.resume!!
        assertEquals("Resume S2 E3", resume.buttonLabel)
    }

    @Test
    fun `progress rows mark episodes watched or part way, and an up next row marks nothing`() = runTest {
        val database = openDb()
        seedShowRow(database)
        val dao = database.watchProgressDao()
        dao.upsert(WatchProgress(StreamType.SERIES, "e1", "s1", 1, 1, 1_450_000L, 1_500_000L, 10L, finishedAt = 10L))
        dao.upsert(WatchProgress(StreamType.SERIES, "e2", "s1", 1, 2, 600_000L, 2_400_000L, 20L))
        dao.upsert(WatchProgress(StreamType.SERIES, "e3", "s1", 2, 1, 0L, 0L, 21L))
        val viewModel = newViewModel(
            database, streamType = StreamType.SERIES, streamId = "s1",
            session = sessionOf(episodesApi(episodesJson)),
        )
        advanceUntilIdle()

        val marks = viewModel.episodeMarks.first { it.size == 3 }
        assertTrue(marks.getValue("e1").watched)
        assertEquals(null, marks.getValue("e1").fraction)
        assertEquals(false, marks.getValue("e2").watched)
        assertEquals(0.25f, marks.getValue("e2").fraction!!, 0.001f)
        assertEquals(600_000L, marks.getValue("e2").positionMillis)
        assertEquals(EpisodeMark(), marks.getValue("e3"))
    }

    @Test
    fun `a failed call shows the error state and retry loads the episodes`() = runTest {
        val database = openDb()
        seedShowRow(database)
        val api = episodesApi(episodesJson).apply { seriesInfoFailure = java.io.IOException("offline") }
        val viewModel = newViewModel(
            database, streamType = StreamType.SERIES, streamId = "s1", session = sessionOf(api),
        )
        advanceUntilIdle()
        viewModel.episodes.first { it == EpisodesState.Failed }
        // The page still opens: the primary action is not lost to the failure.
        assertTrue(viewModel.detail.first { it != null }!!.hasEpisodes)

        api.seriesInfoFailure = null
        viewModel.retryEpisodes()
        advanceUntilIdle()
        assertTrue(viewModel.episodes.first { it is EpisodesState.Loaded } is EpisodesState.Loaded)
    }

    @Test
    fun `a show that answers with no episodes has nothing to play`() = runTest {
        val database = openDb()
        seedShowRow(database)
        val viewModel = newViewModel(
            database, streamType = StreamType.SERIES, streamId = "s1",
            session = sessionOf(episodesApi("{}")),
        )
        advanceUntilIdle()

        val detail = viewModel.detail.first { it?.hasEpisodes == false }!!
        assertEquals(null, detail.resume)
        assertEquals("No episodes yet. Check back later.", showNote(detail))
    }

    @Test
    fun `movies never ask for episodes`() = runTest {
        val database = openDb()
        database.vodStreamDao().replaceAll(listOf(VodStream(id = "a", categoryId = "drama", name = "Alpha")))
        val viewModel = newViewModel(database, streamId = "a", session = sessionOf(episodesApi(episodesJson)))
        advanceUntilIdle()
        assertEquals(EpisodesState.Idle, viewModel.episodes.value)
    }
}
