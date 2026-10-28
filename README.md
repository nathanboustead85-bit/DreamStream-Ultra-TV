# DreamStream Ultra TV — Sports modules integration snapshot

This source tree is a **development integration snapshot**, not a tested full-featured IPTV application. It contains a launch screen, Sports News Hub and Live Sports Finder. It does NOT yet implement the full Xtream login/player/series/movies/themes/kids/voice/score API functionality discussed previously.

## Install from an Android phone

In the `DreamStream-Ultra-TV` GitHub repository, create/replace the files in this archive at their exact paths. Use the source files in this archive as the complete contents of each file. Do not paste the entire archive into one file. Make sure the default branch is `main`, or adjust the workflow trigger.

1. Create/replace `settings.gradle.kts`.
2. Create/replace `build.gradle.kts`.
3. Create/replace `gradle.properties`.
4. Create/replace `app/build.gradle.kts`.
5. Create/replace `app/src/main/AndroidManifest.xml`.
6. Create `app/src/main/res/drawable/tv_banner.xml`.
7. Create/replace `app/src/main/java/com/dreamstream/ultratv/MainActivity.kt`.
8. Create `app/src/main/java/com/dreamstream/ultratv/SportsNewsActivity.kt`.
9. Create `app/src/main/java/com/dreamstream/ultratv/LiveSportsFinderActivity.kt`.
10. Create/replace `.github/workflows/android.yml`.

Run GitHub Actions > Build DreamStream Ultra TV APK > Run workflow. Download the artifact after a successful build. This project has not been compiled here.

## Source/API requirements

- Live Sports Finder: user-provided authorised M3U playlist URL and XMLTV EPG URL (`.xml` or `.xml.gz`). No API key for these sources.
- Sports News: permitted RSS feed URL entered in the screen. No bundled RSS source; rights and terms must be checked.
- Live sports scores and full match/broadcaster mapping: **not implemented**. Requires a sports-data provider, appropriate licensing and likely a backend to protect private API keys.
- Automatic free EPG discovery, multiple EPG source merging, manual channel mapping and 12-hour background refresh: **not implemented** in this snapshot. They require a verified source registry and scheduling/storage code.
- Channels with EPG programme titles matching a favourite are labelled as programme matches; the software cannot prove an actual broadcast from an EPG title alone.
- Channel playback in the standalone Finder delegates to a third-party player after confirmation. This is not integrated Media3 playback.
- Only use streams and guide data you are authorised to access.

## Security note

This development code permits HTTP traffic because previously selected Xtream endpoints use HTTP. Production should narrow cleartext permissions and avoid storing credentials or API secrets in public source code.
