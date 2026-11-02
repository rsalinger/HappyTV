package com.rysal.happytv.ui.main

import com.rysal.happytv.data.network.CategoryDto
import com.rysal.happytv.data.network.EpgListingsResponseDto
import com.rysal.happytv.data.network.LoginResponse
import com.rysal.happytv.data.network.LiveStreamDto
import com.rysal.happytv.data.network.MovieDataDto
import com.rysal.happytv.data.network.SeriesInfoDto
import com.rysal.happytv.data.network.SeriesStreamDto
import com.rysal.happytv.data.network.ServerInfoDto
import com.rysal.happytv.data.network.UserInfoDto
import com.rysal.happytv.data.network.VodInfoDetailDto
import com.rysal.happytv.data.network.VodInfoDto
import com.rysal.happytv.data.network.VodStreamDto
import com.rysal.happytv.data.network.XtreamApi
import com.rysal.happytv.data.network.dtoBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Local copy of the in-memory panel used by the dashboard tests. We don't
 * reuse the data-layer one because it's package-private and lives in a
 * different test package.
 */
internal class FakeXtreamApi : XtreamApi {

    var loginResponse = LoginResponse(
        user_info = UserInfoDto(username = "u", auth = "1", status = "Active"),
        server_info = ServerInfoDto(url = "panel.example", port = "8080"),
    )

    var liveStreamsFailure: Exception? = null
    var vodStreamsFailure: Exception? = null
    var seriesStreamsFailure: Exception? = null
    var seriesCategoriesFailure: Exception? = null

    /**
     * F-50: what `get_vod_info` answers with. The default is the minimal
     * "no info block" shape (`movie_data`-only) — the bug case the fix
     * handles — so existing callers see the safe empty behavior. Tests that
     * want the rich `info` block set this to a fixture-matching DTO.
     */
    var vodInfoResponse: VodInfoDto = VodInfoDto(movie_data = MovieDataDto())

    /** F-50: when set, `get_vod_info` throws (network failure path). */
    var vodInfoFailure: Exception? = null

    /** #68: what `get_series_info` answers with; null keeps the legacy default. */
    var seriesInfoResponse: SeriesInfoDto? = null
    var seriesInfoFailure: Exception? = null

    override suspend fun login(
        endpoint: String,
        username: String,
        password: String,
    ): LoginResponse = loginResponse

    override suspend fun getLiveCategories(
        endpoint: String,
        username: String,
        password: String,
        action: String,
    ) = listOf(CategoryDto(category_id = "1", category_name = "Sports", parent_id = "0"))

    private suspend fun liveStreamDtos(
        endpoint: String,
        username: String,
        password: String,
        categoryId: String?,
        action: String,
    ): List<LiveStreamDto> {
        liveStreamsFailure?.let { throw it }
        return (1..3).map { n ->
            LiveStreamDto(num = "$n", name = "Channel $n", stream_id = "$n", category_id = "1")
        }
    }

    override suspend fun getVodCategories(
        endpoint: String,
        username: String,
        password: String,
        action: String,
    ) = listOf(CategoryDto(category_id = "2", category_name = "Movies", parent_id = "0"))

    private suspend fun vodStreamDtos(
        endpoint: String,
        username: String,
        password: String,
        categoryId: String?,
        action: String,
    ): List<VodStreamDto> {
        vodStreamsFailure?.let { throw it }
        return listOf(
            VodStreamDto(name = "The Matrix", stream_id = "501", category_id = "2", container_extension = "mp4"),
            VodStreamDto(name = "Heat", stream_id = "502", category_id = "2", container_extension = "mkv"),
        )
    }

    override suspend fun getSeriesCategories(
        endpoint: String,
        username: String,
        password: String,
        action: String,
    ): List<CategoryDto> {
        seriesCategoriesFailure?.let { throw it }
        return listOf(CategoryDto(category_id = "3", category_name = "Shows", parent_id = "0"))
    }

    private suspend fun seriesStreamDtos(
        endpoint: String,
        username: String,
        password: String,
        categoryId: String?,
        action: String,
    ): List<SeriesStreamDto> {
        seriesStreamsFailure?.let { throw it }
        return listOf(SeriesStreamDto(name = "Breaking Bad", series_id = "901", category_id = "3"))
    }

    override suspend fun getLiveStreams(endpoint: String, username: String, password: String, categoryId: String?, action: String) =
        dtoBody(liveStreamDtos(endpoint, username, password, categoryId, action))

    override suspend fun getVodStreams(endpoint: String, username: String, password: String, categoryId: String?, action: String) =
        dtoBody(vodStreamDtos(endpoint, username, password, categoryId, action))

    override suspend fun getSeries(endpoint: String, username: String, password: String, categoryId: String?, action: String) =
        dtoBody(seriesStreamDtos(endpoint, username, password, categoryId, action))

    override suspend fun getVodInfo(
        endpoint: String,
        username: String,
        password: String,
        vodId: String,
        action: String,
    ): VodInfoDto {
        vodInfoFailure?.let { throw it }
        return vodInfoResponse
    }

    override suspend fun getSeriesInfo(
        endpoint: String,
        username: String,
        password: String,
        seriesId: String,
        action: String,
    ): SeriesInfoDto {
        seriesInfoFailure?.let { throw it }
        return seriesInfoResponse ?: SeriesInfoDto(
            info = SeriesStreamDto(name = "Breaking Bad", series_id = seriesId, category_id = "3"),
        )
    }

    override suspend fun getShortEpg(
        endpoint: String,
        username: String,
        password: String,
        streamId: String,
        limit: Int,
        action: String,
    ) = EpgListingsResponseDto(epg_listings = emptyList())

    override suspend fun getSimpleDataTable(
        endpoint: String,
        username: String,
        password: String,
        streamId: String,
        action: String,
    ) = EpgListingsResponseDto(epg_listings = emptyList())

    override suspend fun getXmlTv(endpoint: String, username: String, password: String): ResponseBody =
        "<tv/>".toResponseBody()
}