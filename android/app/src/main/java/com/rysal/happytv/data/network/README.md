# data/network — Xtream Codes client

The app's only network package: a Retrofit 2 client for the Xtream Codes v2
Player API, the
`@Serializable` DTOs it speaks, the DTO→Room mappers, and the login + 3-tile
refresh orchestration.

| File | What it is |
| --- | --- |
| `XtreamApi.kt` | Retrofit interface. Every method takes the panel's absolute URL (`@Url`) + credentials as `@Query`, so one Retrofit serves every panel. `buildXtreamApi()` is the singleton factory `Graph` registers at startup. |
| `XtreamDtos.kt` | Wire DTOs. Ids are `String`, numeric-ish fields are `String` coerced by `JsonCoerce`, shape-shifting values (`backdrop_path`, `episodes`) are `JsonElement`. |
| `JsonCoerce.kt` | `toIntOrNull` / `toLongOrNull` / `toDoubleOrNull` / `toBooleanOrNull` for the wire's "number or string, sometimes empty" fields. Never throws. |
| `XtreamMappers.kt` | DTO → Room entity. Blank ids drop the row; blank strings become `null`; EPG `title`/`description` are Base64-decoded when they look like it. |
| `XtreamRepository.kt` | One authenticated session: URL building (`panelOrigin`), all endpoint calls, the cleartext-HTTP one-shot warning, `refreshState` flow. |
| `RefreshState.kt` | `Idle / Loading(n/3) / Done(channels, ms) / Error` + the 3-tile `RefreshProgress` the dashboard collects. |
| `RefreshCoordinator.kt` | `login()` (validates, then writes the secured store + Room profile), `refreshAll()` / `refreshLiveOnly()` … (6 parallel fetches, per-tile Room writes, per-tile states). |

## Manual smoke test against a real provider

Do NOT run these from inside the app build — they are a standalone sanity
check of a panel before (or while) debugging the app. Set your credentials
once in a shell, then run the commands (each line is a complete, runnable
curl; in Android Studio/IntelliJ, ⌘/Ctrl-click runs it):

```bash
export XTREAM_HOST=panel.example.com   # no scheme, or http(s)://… — both work in the app
export XTREAM_PORT=8080
export XTREAM_USER=your_username
export XTREAM_PASS=your_password
export XTREAM_API="http://${XTREAM_HOST}:${XTREAM_PORT}/player_api.php?username=${XTREAM_USER}&password=${XTREAM_PASS}"
```

1. **Login probe** — expect `"auth": 1` and a `user_info` block:

   ```bash
   curl -s "${XTREAM_API}" | head -c 600
   ```

2. **Live categories + streams** — expect JSON arrays; note `category_id`
   (string or number) and `stream_id` (the app stringifies both):

   ```bash
   curl -s "${XTREAM_API}&action=get_live_categories" | head -c 600
   curl -s "${XTREAM_API}&action=get_live_streams" | head -c 600
   curl -s "${XTREAM_API}&action=get_live_streams&category_id=1" | head -c 600
   ```

3. **VOD + series** — `get_vod_info` needs a `vod_id` from the VOD list,
   `get_series_info` a `series_id` from the series list:

   ```bash
   curl -s "${XTREAM_API}&action=get_vod_streams" | head -c 600
   curl -s "${XTREAM_API}&action=get_series" | head -c 600
   curl -s "${XTREAM_API}&action=get_vod_info&vod_id=5001" | head -c 600
   curl -s "${XTREAM_API}&action=get_series_info&series_id=12" | head -c 600
   ```

4. **EPG** — short EPG per channel, or the full XMLTV file (can be tens of MB):

   ```bash
   curl -s "${XTREAM_API}&action=get_short_epg&stream_id=123&limit=4" | head -c 600
   curl -s -o xmltv.xml "http://${XTREAM_HOST}:${XTREAM_PORT}/xmltv.php?username=${XTREAM_USER}&password=${XTREAM_PASS}"
   ```

5. **A stream URL** (the API never returns playable URLs — clients build
   them; live test only if you are comfortable with a raw TS/HLS fetch):

   ```bash
   curl -sI "http://${XTREAM_HOST}:${XTREAM_PORT}/live/${XTREAM_USER}/${XTREAM_PASS}/123.ts"
   ```

What to compare against the app's behaviour:

- `auth: 0` must surface as the panel's `status`/`message` (the app maps
  Expired/Banned/Disabled/wrong-password to text, and stores nothing).
- The six `action=` calls above are exactly the six fetches `refreshAll()`
  runs in parallel; their row counts should match what the dashboard tiles
  report as `Done(channels = N)`.
- Watch `adb logcat -s XtreamRepository RefreshCoordinator` while the app
  refreshes: the cleartext warning fires once per process when the account
  has `useTls=false`, and `refreshAll finished in … ms — N rows` is the
  aggregate of the three tiles.

Notes:

- Credentials travel **in the query string** — that is the protocol, and it
  means they appear in panel access logs and (in debug builds) in logcat via
  the OkHttp logging interceptor. Prefer `https` when the panel serves it;
  the account can be added with TLS on by typing `https://host:port`.
- Some panels return `get_simple_data_table` in a legacy shape; the client
  degrades to an empty list there. If a panel misbehaves, capture the curl
  output above and add it to the canned fixtures in
  `app/src/test/java/com/rysal/happytv/data/network/XtreamApiMockWebServerTest.kt`.

Live end-to-end testing against a real provider is done by the integration
task, not here — this file is the checklist for it.
