package com.rysal.happytv.ui.catalog

import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.data.network.SeriesInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure side of the episode area (#74): ordering, labels, marks. */
class EpisodeModelsTest {

    private fun info(json: String) = SeriesInfoDto(episodes = Graph.json.parseToJsonElement(json))

    @Test
    fun `season labels say Season N and Specials for zero`() {
        assertEquals("Season 1", seasonLabel(1))
        assertEquals("Season 12", seasonLabel(12))
        assertEquals("Specials", seasonLabel(0))
    }

    @Test
    fun `seasons come from the keys of episodes, numerically, Specials last`() {
        val seasons = info("""{"10":[{"id":"a"}],"2":[{"id":"b"}],"0":[{"id":"c"}],"1":[{"id":"d"}]}""").toSeasons()
        assertEquals(listOf(1, 2, 10, 0), seasons.map { it.number })
    }

    @Test
    fun `a season key that is not a number is dropped, an empty season is dropped`() {
        val seasons = info("""{"x":[{"id":"a"}],"1":[],"2":[{"id":"b"}]}""").toSeasons()
        assertEquals(listOf(2), seasons.map { it.number })
    }

    @Test
    fun `episodes without a number take their position, without a title say Episode N`() {
        val season = info("""{"1":[{"id":"a"},{"id":"b","title":"  "},{"id":"c","episode_num":"7","title":"Seven"}]}""")
            .toSeasons().single()
        assertEquals(listOf("a", "b", "c"), season.episodes.map { it.id }.sortedBy { it })
        assertEquals("E1 · Episode 1", season.episodes[0].heading)
        assertEquals("E2 · Episode 2", season.episodes[1].heading)
        assertEquals("E7 · Seven", season.episodes[2].heading)
        assertEquals("S1 E7", season.episodes[2].label)
    }

    @Test
    fun `runtime, plot and thumbnail are optional`() {
        val e = info(
            """{"1":[{"id":"a","episode_num":"1","title":"T","info":{"duration_secs":"2520","plot":"  Long plot  ","movie_image":"/t/abc.jpg"}}]}""",
        ).toSeasons().single().episodes.single()
        assertEquals("42 min", e.runtimeLabel)
        assertEquals("Long plot", e.plot)
        assertEquals("https://image.tmdb.org/t/p/w300/t/abc.jpg", e.thumbnailUrl)

        val bare = info("""{"1":[{"id":"b"}]}""").toSeasons().single().episodes.single()
        assertNull(bare.runtimeLabel)
        assertNull(bare.plot)
        assertNull(bare.thumbnailUrl)
    }

    @Test
    fun `an episode with no id cannot be played and is left out`() {
        val seasons = info("""{"1":[{"title":"No id"}]}""").toSeasons()
        assertTrue(seasons.isEmpty())
    }

    @Test
    fun `junk in episodes gives no seasons`() {
        assertTrue(info("[]").toSeasons().isEmpty())
        assertTrue(SeriesInfoDto().toSeasons().isEmpty())
    }

    @Test
    fun `the opening season holds the resume episode, else the first`() {
        val seasons = info("""{"1":[{"id":"a"}],"2":[{"id":"b"}],"0":[{"id":"c"}]}""").toSeasons()
        assertEquals(2, initialSeason(seasons, "b"))
        assertEquals(0, initialSeason(seasons, "c"))
        assertEquals(1, initialSeason(seasons, "gone"))
        assertEquals(1, initialSeason(seasons, null))
        assertNull(initialSeason(emptyList(), null))
        assertEquals("a", firstEpisode(seasons)?.id)
    }

    @Test
    fun `marks follow the progress rows`() {
        fun row(id: String, pos: Long, dur: Long, finished: Long? = null) =
            WatchProgress(StreamType.SERIES, id, "s", 1, 1, pos, dur, 1L, finishedAt = finished)
        val marks = marksOf(
            listOf(row("done", 2_300_000L, 2_400_000L, finished = 5L), row("half", 1_200_000L, 2_400_000L), row("next", 0L, 0L)),
        )
        assertTrue(marks.getValue("done").watched)
        assertEquals(0.5f, marks.getValue("half").fraction!!, 0.001f)
        assertEquals(1_200_000L, marks.getValue("half").positionMillis)
        assertEquals(EpisodeMark(), marks.getValue("next"))
    }

    private fun clean(raw: String, name: String?, n: Int = 1) = cleanEpisodeTitle(raw, listOf(name), n)

    @Test
    fun `real provider shapes lose the show name and the code`() {
        assertEquals("Day 2", clean("Big Brother - S01E02 - Day 2", "Big Brother (2023)", 2))
        assertEquals("Episode 1", clean("Gogglebox S01E01", "Gogglebox (2013)", 1))
        assertEquals("Episode 1", clean("Have I Got News for You S02E01", "Have I Got News for You", 1))
        assertEquals(
            "Episode 3",
            clean("The Iceblade Sorcerer Shall Rule the World S01E03", "The Iceblade Sorcerer Shall Rule the World", 3),
        )
        assertEquals("Title", clean("S01E05 - Title", "Show", 5))
        assertEquals("Title", clean("Show 1x05 - Title", "Show", 5))
        assertEquals("Title only", clean("Title only", "Show"))
    }

    @Test
    fun `only text after the last token is kept, and tokens are matched loosely`() {
        assertEquals("Day 2", clean("BIG BROTHER: s1e2 \u00b7 Day 2", "Big Brother"))
        assertEquals("Finale.", clean("Show S01E01 - Recap S01E02 | Finale.", "Show"))
        assertEquals("Day 4", clean("Big Brother S1 E4: Day 4", "Big Brother"))
    }

    @Test
    fun `a leading name goes, with or without the year, and the grid name counts too`() {
        assertEquals("The Launch", cleanEpisodeTitle("Big Brother (2023) - The Launch", listOf("Big Brother (2023)"), 1))
        assertEquals("The Launch", cleanEpisodeTitle("Big Brother - The Launch", listOf("Big Brother [2023]"), 1))
        assertEquals("The Launch", cleanEpisodeTitle("BIG BROTHER: The Launch", listOf("Other", "Big Brother"), 1))
        // Not a name prefix of a longer word.
        assertEquals("Big Brotherhood", clean("Big Brotherhood", "Big Brother"))
    }

    @Test
    fun `a title keeps its own trailing punctuation, only the seam is stripped (26)`() {
        assertEquals("ROMULUS S.V.", clean("ROMULUS S.V.", "Show"))
        assertEquals("Title.", clean("Show - S01E02 - Title.", "Show", 2))
        assertEquals("To be continued...", clean("Show S01E03 - To be continued...", "Show", 3))
        assertEquals("To be continued...", clean("Show - To be continued...", "Show", 3))
        assertEquals("Pilot.", clean("Show. Pilot.", "Show"))
        assertEquals("Title.", clean("Title.  ", "Show"))
        assertEquals("Episode 1", clean("Show S01E01 -", "Show", 1))
        assertEquals("Episode 4", clean("Show.", "Show", 4))
        assertEquals("Episode 4", clean("Show S01E04 ...", "Show", 4))
    }

    @Test
    fun `a title is never empty`() {
        assertEquals("Episode 2", clean("", "Show", 2))
        assertEquals("Episode 2", clean("   ", null, 2))
        assertEquals("Episode 2", clean("S01E02", "Show", 2))
        assertEquals("Episode 2", clean("Show", "Show", 2))
        assertEquals("Episode 2", clean("Show - 2", "Show", 2))
    }

    @Test
    fun `a title that was only the code reads Episode N in the list`() {
        val season = info("""{"1":[{"id":"a","episode_num":"2","title":"Big Brother - S01E02"}]}""")
            .toSeasons(listOf("Big Brother (2023)")).single()
        assertEquals("E2 \u00b7 Episode 2", season.episodes.single().heading)
        val named = info("""{"1":[{"id":"a","episode_num":"2","title":"Big Brother - S01E02 - Day 2"}]}""")
            .toSeasons(listOf("Big Brother (2023)")).single()
        assertEquals("E2 \u00b7 Day 2", named.episodes.single().heading)
    }
}
