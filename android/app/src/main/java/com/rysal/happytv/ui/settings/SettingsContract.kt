package com.rysal.happytv.ui.settings

/**
 * The intent contract [SettingsActivity] exposes to the rest of the app.
 *
 * The TV guide's empty state ("No guide yet" → **Open guide settings**) is the
 * one in-app caller today: it starts [SettingsActivity] with
 * [EXTRA_SETTINGS_SECTION] = [SECTION_GUIDE] so the pane the user asked for is
 * already open. Any other screen can do the same with the other section values.
 */

/** Extra carrying which settings section to open; see [SettingsSection.extra]. */
const val EXTRA_SETTINGS_SECTION = "com.rysal.happytv.extra.SETTINGS_SECTION"

const val SECTION_ACCOUNT = "account"
const val SECTION_REFRESH = "refresh"
const val SECTION_LIVE_TV = "livetv"
const val SECTION_GUIDE = "guide"
const val SECTION_HIDDEN = "hidden"
const val SECTION_PLAYER = "player"
const val SECTION_ABOUT = "about"

/**
 * The seven left-hand sections, in the design spec §"Settings" order. [extra] is the
 * value that rides in [EXTRA_SETTINGS_SECTION]; [label] is the rail item text.
 */
enum class SettingsSection(val label: String, val extra: String) {
    ACCOUNT("Account", SECTION_ACCOUNT),
    REFRESH("Refresh", SECTION_REFRESH),
    LIVE_TV("Live TV", SECTION_LIVE_TV),
    GUIDE("Guide", SECTION_GUIDE),
    HIDDEN("Hidden items", SECTION_HIDDEN),
    PLAYER("Player", SECTION_PLAYER),
    ABOUT("About", SECTION_ABOUT),
    ;

    companion object {
        /** Unknown/absent extra → the Account section (the screen's default). */
        fun fromExtra(raw: String?): SettingsSection =
            entries.firstOrNull { it.extra == raw } ?: ACCOUNT
    }
}

/**
 * The Guide section's two sources. The stored setting is a single fact —
 * `settings.epgUrl` — because `data/guide` already derives "use XMLTV" from
 * "a link is set"; this enum is just the segmented control's view of it.
 */
enum class GuideSource(val label: String) {
    PROVIDER("From provider"),
    XMLTV("XMLTV link"),
}

/** Stream format options (design spec §"Settings > Refresh"). */
enum class StreamFormatOption(val label: String, val stored: String) {
    ADAPTIVE("Adaptive (m3u8)", "m3u8"),
    TS("TS", "ts"),
    ;

    companion object {
        fun fromStored(raw: String): StreamFormatOption =
            entries.firstOrNull { it.stored == raw } ?: ADAPTIVE
    }
}

/** "Update the guide every" options, in the order the segmented control draws them. */
enum class GuideRefreshOption(val label: String, val hours: Int) {
    HOURS_6("6 hours", 6),
    HOURS_12("12 hours", 12),
    HOURS_24("24 hours", 24),
    ;

    companion object {
        fun fromHours(hours: Int): GuideRefreshOption =
            entries.firstOrNull { it.hours == hours } ?: HOURS_12
    }
}

/** Aspect ratio options (design spec §"Settings > Player"). */
enum class AspectRatioOption(val label: String, val stored: String) {
    AUTO("Auto", "auto"),
    WIDE("16:9", "16:9"),
    CLASSIC("4:3", "4:3"),
    ;

    companion object {
        fun fromStored(raw: String): AspectRatioOption =
            entries.firstOrNull { it.stored == raw } ?: AUTO
    }
}
