package com.rysal.happytv.data.network

import com.rysal.happytv.data.Graph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-50 parser regression: `get_vod_info` returns `{info, movie_data}` and the
 * two halves are NOT symmetric. `movie_data` only repeats the `get_vod_streams`
 * subset (stream ids + `container_extension`); every field the movie detail
 * page renders — `movie_image`, `plot`, `genre`, `cast`, `director`, `duration`,
 * `releasedate`, `youtube_trailer`, `tmdb_id` — lives in `info`. A parser that
 * reads `movie_data` for those fields gets nulls on every one, which is
 * exactly the "clicking a movie shows no detail" symptom F-50 filed.
 *
 * The fixture below is the panel ground truth recorded in the F-50 finding:
 * rich `info`, ids-only `movie_data`. The assertions pin each mapped field to
 * its `info` source (and would fail with a non-null value if a future refactor
 * flipped the parser to read `movie_data`).
 */
class VodDetailParserTest {

    /**
     * Real panel response shape (F-50 finding, panel ground truth):
     * `info` carries the metadata, `movie_data` carries only stream ids.
     * The fixture also exercises the coercion rules the DTOs exist for —
     * `duration_secs` as a quoted number, `rating` as a quoted decimal.
     */
    private val fixture = """
        {
          "info": {
            "tmdb_id": "603",
            "imdb_id": "tt0133093",
            "movie_image": "https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
            "cover_big": "https://image.tmdb.org/t/p/w780/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
            "backdrop_path": [
              "https://image.tmdb.org/t/p/w780/lh5lbisD4oDbEKgUxoRaZQGKqJQ.jpg",
              "https://image.tmdb.org/t/p/w780/nRf4goZVLUm7ek3qvhqjRiJ8cFd.jpg"
            ],
            "youtube_trailer": "vKQi3bBA1y8",
            "genre": "Action, Sci-Fi",
            "plot": "A computer hacker learns from mysterious rebels about the true nature of his reality.",
            "cast": "Keanu Reeves, Laurence Fishburne, Carrie-Anne Moss",
            "director": "Lana Wachowski, Lilly Wachowski",
            "rating": "8.7",
            "releasedate": "1999-03-31",
            "duration_secs": "8160",
            "duration": "02:16:00",
            "bitrate": "2500",
            "age": "16+",
            "rating_mpaa": "R"
          },
          "movie_data": {
            "stream_id": "501",
            "name": "The Matrix",
            "added": "1615446000",
            "category_id": "2",
            "container_extension": "mp4",
            "custom_sid": "",
            "direct_source": ""
          }
        }
    """.trimIndent()

    private fun parseFixture(): VodInfoDto = Graph.json.decodeFromString(fixture)

    @Test
    fun `info block carries the metadata, movie_data does not`() {
        val dto = parseFixture()

        // The bug F-50 filed: a parser reading `movie_data` for these fields
        // sees nulls. We assert the *opposite* — `info` is where they live.
        assertNotNull("info block must be present", dto.info)
        assertEquals("603", dto.info?.tmdb_id)
        assertNotNull("info.movie_image (poster)", dto.info?.movie_image)
        assertNotNull("info.plot", dto.info?.plot)
        assertNotNull("info.genre", dto.info?.genre)
        assertNotNull("info.cast", dto.info?.cast)
        assertNotNull("info.director", dto.info?.director)
        assertNotNull("info.duration", dto.info?.duration)
        assertNotNull("info.releasedate", dto.info?.releasedate)
        assertNotNull("info.youtube_trailer", dto.info?.youtube_trailer)

        // `movie_data` is the ids-only half. `MovieDataDto` does not model
        // plot/genre/poster at all — that is the type-level guarantee the
        // fix leans on — so the assertable part is just that it carries the
        // stream id + container_extension the play URL needs.
        assertEquals("501", dto.movie_data?.stream_id)
        assertEquals("mp4", dto.movie_data?.container_extension)
    }

    @Test
    fun `toDetail maps movie_image to poster, plot to overview, and splits genre and cast`() {
        val detail = parseFixture().toDetail()

        assertNotNull("toDetail must not return null when info is present", detail)
        assertEquals(
            "movie_image → poster (absolute URL passes through untouched)",
            "https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
            detail?.poster,
        )
        assertEquals(
            "plot → overview",
            "A computer hacker learns from mysterious rebels about the true nature of his reality.",
            detail?.overview,
        )
        assertEquals(
            "genre → genres (comma-separated → list, trimmed)",
            listOf("Action", "Sci-Fi"),
            detail?.genres,
        )
        assertEquals(
            "cast → cast (comma-separated → list, trimmed)",
            listOf("Keanu Reeves", "Laurence Fishburne", "Carrie-Anne Moss"),
            detail?.cast,
        )
        assertEquals("director is preserved", "Lana Wachowski, Lilly Wachowski", detail?.director)
    }

    @Test
    fun `toDetail maps duration and releasedate`() {
        val detail = parseFixture().toDetail()

        assertEquals(
            "duration (HH:MM:SS) is preserved as the runtime label",
            "02:16:00",
            detail?.duration,
        )
        assertEquals(
            "releasedate is preserved raw (YYYY-MM-DD); the UI slices the year",
            "1999-03-31",
            detail?.releasedate,
        )
        assertEquals("year is the first four chars of releasedate", "1999", detail?.releasedate?.take(4))
    }

    @Test
    fun `toDetail falls back to duration_secs and rating_mpaa when the preferred fields are blank`() {
        val detail = Graph.json
            .decodeFromString<VodInfoDto>(
                """
                {
                  "info": {
                    "duration": "",
                    "duration_secs": "8160",
                    "age": "",
                    "rating_mpaa": "R",
                    "plot": "",
                    "description": "The plot lives in description on this panel."
                  },
                  "movie_data": { "stream_id": "501" }
                }
                """.trimIndent(),
            )
            .toDetail()

        assertEquals("duration falls back to duration_secs rendered as Ns", "8160s", detail?.duration)
        assertEquals("age falls back to rating_mpaa", "R", detail?.age)
        assertEquals(
            "overview falls back to description when plot is blank",
            "The plot lives in description on this panel.",
            detail?.overview,
        )
    }

    @Test
    fun `movie_image tmdb-relative path resolves to the absolute tmdb url`() {
        val detail = Graph.json
            .decodeFromString<VodInfoDto>(
                """
                {
                  "info": { "movie_image": "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg" },
                  "movie_data": { "stream_id": "1" }
                }
                """.trimIndent(),
            )
            .toDetail()

        assertEquals(
            "TMDB-relative path resolves through image.tmdb.org at the requested size",
            "https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
            detail?.poster,
        )
    }

    @Test
    fun `movie_data-only response has no detail — caller falls back to missing-info state`() {
        // The bug shape: the panel sent `movie_data` (ids only) and no `info`.
        // `toDetail` must return null, not a half-empty VodDetail, so the
        // ViewModel's "missing info" fallback is what the user sees.
        val detail = Graph.json
            .decodeFromString<VodInfoDto>(
                """
                {
                  "movie_data": {
                    "stream_id": "501",
                    "name": "The Matrix",
                    "container_extension": "mp4"
                  }
                }
                """.trimIndent(),
            )
            .toDetail()

        assertNull(
            "no info block → toDetail returns null (missing-info state), never a blank detail",
            detail,
        )
    }

    @Test
    fun `toDetail reads info, not movie_data, even when movie_data carries more keys`() {
        // Regression guard: some panels repeat name/category on `movie_data`.
        // The parser must still source plot/poster/etc from `info`, and must
        // not surface `movie_data.name` as the plot.
        val detail = Graph.json
            .decodeFromString<VodInfoDto>(
                """
                {
                  "info": {
                    "movie_image": "https://img.example/poster.jpg",
                    "plot": "Real plot from info.",
                    "genre": "Drama"
                  },
                  "movie_data": {
                    "stream_id": "501",
                    "name": "Movie title from movie_data",
                    "added": "1615446000",
                    "category_id": "2",
                    "container_extension": "mkv"
                  }
                }
                """.trimIndent(),
            )
            .toDetail()

        assertEquals("overview comes from info.plot", "Real plot from info.", detail?.overview)
        assertEquals("poster comes from info.movie_image", "https://img.example/poster.jpg", detail?.poster)
        assertEquals("genres come from info.genre", listOf("Drama"), detail?.genres)
    }

    @Test
    fun `backdrop_path is typed out as a list of urls`() {
        val detail = parseFixture().toDetail()
        assertTrue("backdrops are surfaced from info.backdrop_path", detail?.backdrops?.isNotEmpty() == true)
        assertEquals(2, detail?.backdrops?.size)
    }
}
