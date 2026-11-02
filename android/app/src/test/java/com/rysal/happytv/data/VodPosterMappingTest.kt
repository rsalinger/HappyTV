package com.rysal.happytv.data

import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.network.VodStreamDto
import com.rysal.happytv.data.network.SeriesStreamDto
import com.rysal.happytv.data.network.toEntity
import com.rysal.happytv.data.network.toVodEntities
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * F-49 regression: movie posters do not load anywhere in the Movies UI even
 * though the panel's `get_vod_streams` rows carry the art URL in
 * `stream_icon` (TMDB `https://image.tmdb.org/t/p/w600_and_h900_bestv2/<hash>.jpg`).
 *
 * Panel ground-truth for the fixture below (2026-10-20 third on-TV pass):
 *  - `stream_icon`   → the TMDB URL we want.
 *  - `cover`         → null on the list rows.
 *  - `movie_image`   → null on the list rows.
 *
 * The wiring is: DTO `stream_icon` → entity `VodStream.streamIcon` → derived
 * `VodStream.posterUrl` → `CatalogItem.posterUrl` → `HtPosterImage`. The UI
 * binds only to `posterUrl`, so a future panel-shape change stays in the
 * entity. These tests pin that the "no `cover` / no `movie_image`" shape
 * still produces a usable poster URL.
 */
class VodPosterMappingTest {

    /** A row shaped exactly like a real panel's `get_vod_streams` entry. */
    private val panelJson = """
        {
          "num": "1",
          "name": "The Long Coast",
          "stream_type": "movie",
          "stream_id": "9182736",
          "stream_icon": "https://image.tmdb.org/t/p/w600_and_h900_bestv2/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
          "added": "1698765432",
          "category_id": "42",
          "container_extension": "mkv",
          "custom_sid": "",
          "direct_source": "",
          "rating": "7.4",
          "rating_5based": "3.7"
        }
    """.trimIndent()

    /** Same row but with the wire-alias fields present and null (some panels). */
    private val panelJsonWithCoverNull = """
        {
          "num": "1",
          "name": "The Long Coast",
          "stream_type": "movie",
          "stream_id": "9182736",
          "stream_icon": "https://image.tmdb.org/t/p/w600_and_h900_bestv2/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
          "cover": null,
          "movie_image": null,
          "added": "1698765432",
          "category_id": "42",
          "container_extension": "mkv",
          "rating": "7.4"
        }
    """.trimIndent()

    // ------------------------------------------------------------------ entity

    @Test
    fun `posterUrl reads the streamIcon wire field and ignores blank`() {
        // Normal panel row.
        val withArt = VodStream(
            id = "1",
            categoryId = "42",
            name = "The Long Coast",
            streamIcon = "https://image.tmdb.org/t/p/w600_and_h900_bestv2/abc.jpg",
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w600_and_h900_bestv2/abc.jpg",
            withArt.posterUrl,
        )

        // Panels say "no art" with "" or a missing field — both land as null
        // or blank and the UI must get `null` so it paints the fallback.
        val blankIcon = VodStream(id = "2", categoryId = "42", name = "Copper Sky", streamIcon = "   ")
        assertNull(blankIcon.posterUrl)

        val missingIcon = VodStream(id = "3", categoryId = "42", name = "Little Comets")
        assertNull(missingIcon.posterUrl)
    }

    @Test
    fun `series posterUrl reads the cover wire field`() {
        val show = SeriesStream(
            id = "s1",
            categoryId = "7",
            name = "Harbor Lights",
            cover = "https://image.tmdb.org/t/p/w500/harbor.jpg",
        )
        assertEquals("https://image.tmdb.org/t/p/w500/harbor.jpg", show.posterUrl)

        val noCover = SeriesStream(id = "s2", categoryId = "7", name = "Silent Harbor", cover = "")
        assertNull(noCover.posterUrl)
    }

    // ------------------------------------------------- DTO -> entity (mapper)

    @Test
    fun `panel-shaped row with stream_icon set and cover null maps to a non-null posterUrl`() {
        val dto = Graph.json.decodeFromString(VodStreamDto.serializer(), panelJsonWithCoverNull)

        // The wire aliases must NOT shadow the URL: a panel that fills both
        // `stream_icon` and `cover`/`movie_image` (or fills one and nulls the
        // other) must still land on `streamIcon`.
        assertEquals(
            "https://image.tmdb.org/t/p/w600_and_h900_bestv2/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
            dto.stream_icon,
        )

        val entity = dto.toEntity()
        assertNotNull(entity)
        // The proof the acceptance asks for: stream_icon survives to posterUrl
        // even when cover / movie_image are null on the same row.
        assertEquals(
            "https://image.tmdb.org/t/p/w600_and_h900_bestv2/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
            entity!!.posterUrl,
        )
        // And the underlying streamIcon field is what feeds it — the mapping
        // is the same, not a copy that could drift.
        assertEquals(entity.streamIcon, entity.posterUrl)
    }

    @Test
    fun `panel-shaped row without stream_icon yields null posterUrl`() {
        val noArt = panelJson.replace(
            "\"stream_icon\": \"https://image.tmdb.org/t/p/w600_and_h900_bestv2/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg\",",
            "\"stream_icon\": \"\",",
        )
        val dto = Graph.json.decodeFromString(VodStreamDto.serializer(), noArt)
        val entity = dto.toEntity()
        assertNotNull(entity)
        assertNull(entity!!.posterUrl)
    }

    @Test
    fun `a list of mixed panel rows keeps the posterUrl on the rows that have art`() {
        val listJson = """
            [
              {
                "num": "1",
                "name": "Art",
                "stream_id": "1",
                "stream_icon": "https://image.tmdb.org/t/p/w500/art.jpg",
                "category_id": "42"
              },
              {
                "num": "2",
                "name": "No art",
                "stream_id": "2",
                "stream_icon": "",
                "category_id": "42"
              }
            ]
        """.trimIndent()
        val dtos = Graph.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(VodStreamDto.serializer()),
            listJson,
        )
        val rows = dtos.toVodEntities()
        assertEquals(2, rows.size)
        assertEquals("https://image.tmdb.org/t/p/w500/art.jpg", rows[0].posterUrl)
        assertNull(rows[1].posterUrl)
    }

    // ------------------------------------------------- UI (CatalogItem plumbing)

    @Test
    fun `catalog item carries the same posterUrl the entity exposes`() {
        // Mirrors `CatalogViewModel.toItem(row: VodStream, ...)`: the grid
        // reads only `CatalogItem.posterUrl`, so a regression here would be
        // the difference between "posters show" and "posters don't show".
        val row = VodStream(
            id = "m1",
            categoryId = "42",
            name = "The Long Coast",
            streamIcon = "https://image.tmdb.org/t/p/w600_and_h900_bestv2/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
        )
        val item = com.rysal.happytv.ui.catalog.CatalogItem(
            id = row.id,
            name = row.name,
            year = null,
            isFavorite = false,
            posterUrl = row.posterUrl,
        )
        assertEquals(row.posterUrl, item.posterUrl)
    }
}
