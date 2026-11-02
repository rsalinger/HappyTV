package com.rysal.happytv.ui.browse

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.guide.GuideRepository
import com.rysal.happytv.data.guide.HttpXmlTvFetcher
import com.rysal.happytv.ui.guide.GuideViewModel
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [GuideViewModel] on the test's in-memory [db], for tests that mount
 * [LiveTvScreen]. The screen builds its guide view model eagerly and, without
 * a factory, reaches for `Graph`, which only exists in the running app (#119).
 */
internal fun testGuideViewModel(context: Context, db: HappyTvDatabase, now: () -> Long = { 0L }): GuideViewModel {
    val dataStore = PreferenceDataStoreFactory.create {
        File(context.cacheDir, "test-guide-${dataStoreFiles.getAndIncrement()}.preferences_pb")
    }
    return GuideViewModel(
        GuideRepository(
            epgDao = db.epgProgramDao(),
            liveDao = db.liveStreamDao(),
            categoryDao = db.categoryDao(),
            favoriteDao = db.favoriteDao(),
            recentDao = db.recentChannelDao(),
            settings = SettingsRepository(dataStore),
            session = { null },
            xmlTvFetcher = HttpXmlTvFetcher(context),
        ),
        ZoneId.systemDefault(),
        now,
    )
}

internal fun factoryOf(viewModel: ViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
}

private val dataStoreFiles = AtomicInteger()
