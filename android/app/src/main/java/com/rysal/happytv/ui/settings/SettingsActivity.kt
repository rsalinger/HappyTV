package com.rysal.happytv.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.rysal.happytv.MainActivity
import com.rysal.happytv.ui.login.LoginActivity
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme

/**
 * Settings host (design spec §"Screens: settings").
 *
 * Opened from the dashboard's Settings card and from the guide's empty state,
 * which passes [EXTRA_SETTINGS_SECTION] so the right pane is already showing.
 *
 * Exit paths:
 *  - "Refresh everything now?" → back to the dashboard with all three tiles
 *    refreshing ([MainActivity] on top, everything above it cleared).
 *  - "Sign out of Home?" → [LoginActivity], whole stack replaced.
 */
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.factory(
            startSection = SettingsSection.fromExtra(intent.getStringExtra(EXTRA_SETTINGS_SECTION)),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    SettingsScreen(
                        onBack = { finish() },
                        onGoToDashboard = { goToDashboard() },
                        onGoToSignIn = { goToSignIn() },
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    /**
     * "Confirming returns to the dashboard, where all three tiles show their
     * first-load refresh" — the refresh itself is already running (the
     * view model started it on the application scope), so this only has to
     * surface [MainActivity] over whatever else is on the stack.
     */
    private fun goToDashboard() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    /** "Confirming wipes the saved credentials and lists and opens sign in." */
    private fun goToSignIn() {
        startActivity(
            LoginActivity.signInIntent(this).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            },
        )
        finish()
    }
}
