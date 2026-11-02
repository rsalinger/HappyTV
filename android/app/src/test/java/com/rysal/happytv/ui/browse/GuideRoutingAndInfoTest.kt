package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.data.guide.GuideWindow
import com.rysal.happytv.ui.guide.GuideFocus
import com.rysal.happytv.ui.guide.buildGuideRow
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Issue #15's wiring that is not the Compose screen: where the dashboard's two
 * tiles go, that `GuideActivity` is really gone, and what the info band asks the
 * guide store for.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuideRoutingAndInfoTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    // ---------------------------------------------------------------- routing

    @Test
    fun `the TV guide tile opens Live TV in the guide view`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = LiveTvActivity.guideIntent(context)

        assertEquals(LiveTvActivity::class.java.name, intent.component?.className)
        assertEquals(LiveTvActivity.VIEW_GUIDE, intent.getStringExtra(LiveTvActivity.EXTRA_START_VIEW))
    }

    @Test
    fun `the Live TV tile opens the remembered view - no start view is asked for`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = LiveTvActivity.liveTvIntent(context)

        assertEquals(LiveTvActivity::class.java.name, intent.component?.className)
        assertNull(intent.getStringExtra(LiveTvActivity.EXTRA_START_VIEW))
    }

    @Test
    fun `GuideActivity is retired - no class, no manifest entry`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("the manifest still declares GuideActivity", manifest.contains("GuideActivity"))
        assertFalse(File("src/main/java/com/rysal/happytv/ui/guide/GuideActivity.kt").exists())
        assertFalse(File("src/main/java/com/rysal/happytv/ui/guide/GuideScreen.kt").exists())
    }

    // -------------------------------------------------------------- info band

    private fun stream(
        id: String = "100",
        num: Int? = 100,
        name: String = "Northbay News",
        replay: Boolean = true,
        epg: String? = "northbay.example",
        category: String = "news",
    ) = LiveStream(id = id, categoryId = category, num = num, name = name, tvArchive = replay, epgChannelId = epg)

    @Test
    fun `a list row with a now line asks for that programme and draws what it knows at once`() {
        val row = LiveChannelRow(stream(), nowTitle = "Headline report", nowUntilLabel = "until 9:00")

        val target = infoTargetForList(row, now)

        assertEquals("100 · Northbay News", target.band.channelLine)
        assertEquals("Headline report", target.band.title)
        assertEquals("On now · until 9:00", target.band.timeLine)
        val query = target.query!!
        assertEquals("100", query.streamId)
        assertEquals("northbay.example", query.epgChannelId)
        assertEquals(now, query.atUtcMillis)
        assertTrue(query.hasReplay)
    }

    @Test
    fun `a list row without a now line is No guide info and asks for nothing`() {
        val target = infoTargetForList(LiveChannelRow(stream(replay = false)), now)

        assertEquals("No guide info", target.band.title)
        assertFalse(target.band.hasProgram)
        assertNull(target.band.timeLine)
        assertNull("nothing to read", target.query)
    }

    private fun guideRow(): com.rysal.happytv.ui.guide.GuideRow {
        val channel = GuideChannel(
            streamId = "100", num = 100, name = "Northbay News", genre = "News", isFavorite = false,
            hasReplay = true, epgChannelId = "northbay.example",
        )
        val window = GuideWindow.fromNow(now, zone)
        return buildGuideRow(
            channel,
            listOf(
                GuideProgram("100", "Headline report", null, now - 10 * minute, now + 20 * minute),
                GuideProgram("100", "Weather watch", null, now + 20 * minute, now + 50 * minute),
            ),
            guideCount = 2,
            window = window,
            nowUtcMillis = now,
            zone = zone,
        )
    }

    @Test
    fun `a focused program asks for exactly that programme`() {
        val row = guideRow()
        val weather = now + 20 * minute

        val target = infoTargetForGuide(GuideFocus.Program(0, weather), listOf(row), now)!!

        assertEquals("Weather watch", target.band.title)
        val query = target.query!!
        assertEquals(weather, query.atUtcMillis)
        assertEquals("100", query.streamId)
        assertEquals("northbay.example", query.epgChannelId)
    }

    @Test
    fun `a focused channel cell asks for what is on now`() {
        val row = guideRow()

        val target = infoTargetForGuide(GuideFocus.ChannelCell(0), listOf(row), now)!!

        assertEquals("Headline report", target.band.title)
        assertEquals(now - 10 * minute, target.query!!.atUtcMillis)
    }

    @Test
    fun `a focused channel cell with nothing on asks for nothing`() {
        val channel = GuideChannel("100", 100, "Northbay News", null, false, false, null)
        val row = buildGuideRow(channel, emptyList(), 0, GuideWindow.fromNow(now, zone), now, zone)

        val target = infoTargetForGuide(GuideFocus.ChannelCell(0), listOf(row), now)!!

        assertEquals("No guide info", target.band.title)
        assertNull(target.query)
        assertNull(infoTargetForGuide(GuideFocus.ChannelCell(4), listOf(row), now))
    }

    @Test
    fun `Live TV's rows become guide channels with the genre from the category name`() {
        val rows = listOf(
            LiveChannelRow(stream(id = "100", category = "news"), isFavorite = true),
            LiveChannelRow(stream(id = "300", name = "Fieldhouse Sports", num = null, replay = false, category = "sports")),
            LiveChannelRow(stream(id = "400", name = "Mystery", category = "other")),
        )
        val categories = listOf(
            LiveCategoryItem.ProviderCategory("news", "News", 2, false, LiveCategorySelection.Category("news", "News")),
            LiveCategoryItem.ProviderCategory("sports", "Sports", 1, false, LiveCategorySelection.Category("sports", "Sports")),
            LiveCategoryItem.ProviderCategory("other", "Entertainment", 1, false, LiveCategorySelection.Category("other", "Entertainment")),
        )

        val channels = rows.toGuideChannels(categories)

        assertEquals(listOf("100", "300", "400"), channels.map { it.streamId })
        assertEquals(listOf("News", "Sports", null), channels.map { it.genre })
        assertEquals(listOf(true, false, false), channels.map { it.isFavorite })
        assertEquals(listOf(true, false, true), channels.map { it.hasReplay })
        assertEquals("News", channels.first().categoryName)
        assertNotNull(channels.first().epgChannelId)
        assertNull(channels[1].num)
    }
}
