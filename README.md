# DreamStream Ultra TV — starter project

Android TV / Fire TV starter with D-pad focusable menu and GitHub Actions debug APK build.

**Current status:** foundation only. Live TV, Movies, Series, login, settings, favourites, and playback are placeholders and are NOT yet implemented. No streaming credentials or server addresses are included.

## Build
Push files to the root of a GitHub repository on the `main` branch. GitHub Actions builds `app-debug.apk` and uploads it as an artifact. This build has not been verified in this environment.

## Upload from a phone
GitHub's browser uploader accepts individual files but does not extract ZIP archives. Extract this ZIP first, then upload the project tree while preserving directories, including `.github/workflows/android.yml`. An alternative is to use a Git client that supports committing folders from Android.
