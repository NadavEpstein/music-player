# Offline Music Player for Xiaomi POCO X7 Pro

A lightweight, 100% offline native Android music player built specifically for the **Xiaomi POCO X7 Pro** (Android 15 / API 35, Xiaomi HyperOS 2).

## Core Architecture & Features

- **100% Offline by Design**: Zero network permissions (`android.permission.INTERNET` and `ACCESS_NETWORK_STATE` are completely absent from `AndroidManifest.xml`). No accounts, telemetry, ads, or cloud APIs. Fully operational in Airplane Mode.
- **Exclusive Music Directory (`/storage/emulated/0/Music`)**: Uses Android's Storage Access Framework (`Intent.ACTION_OPEN_DOCUMENT_TREE` pre-targeted to `primary:Music`) and persists URI permissions across device restarts via `takePersistableUriPermission`. Never scans `MediaStore`, `Downloads`, or external folders.
- **Strict Two-Level Directory Hierarchy**:
  - **Level 0 (`Music/<file>`)**: Audio files directly inside root `Music/` appear exclusively under **Standalone Songs** at the bottom of the Library.
  - **Level 1 (`Music/<Subfolder>/<file>`)**: Each immediate subfolder inside `Music/` is automatically discovered as a **Playlist** at the top of the Library, containing only the audio files directly inside that subfolder.
  - **Level 2+ Ignored (`Music/<Subfolder>/<DeeperFolder>/...`)**: Any subfolder inside a playlist folder and all files inside deeper subfolders are completely ignored across scanning, search, playlist views, and queue operations.
- **Folder-Based Playlists & Favorites**:
  - Creating a playlist creates a direct subfolder in `Music/`.
  - Renaming a playlist renames its subfolder in `Music/`.
  - Deleting a playlist safely checks every audio file inside the subfolder by SHA-256 content hash and copies any unique song (not already in root `Music/`) back into root `Music/` before removing the subfolder, ensuring original music files are never lost.
  - Adding a song to a playlist copies the audio stream into the target subfolder without moving or deleting the original file, using SHA-256 content hashing to prevent duplicates.
  - **Favorites** is implemented as a dedicated `Music/Favorites` subfolder with quick top-bar access and long-press queue integration.
- **Full Hebrew & Unicode Compatibility**: Normalizes all filenames, folder names, and metadata tags to Unicode NFC, supports right-to-left (`RTL`) and mixed Hebrew/English (`BiDi`) rendering (`android:supportsRtl="true"`, `android:textDirection="locale"`), and preserves Hebrew characters across file copy, search, and playlist operations.
- **Minimalist Main Playback Screen**:
  - Displays strictly the **Song Title**, **Progress Bar**, **Play/Pause Button**, and **Shuffle Toggle** centered on screen, with unobtrusive navigation for Library, Favorites, Theme switching, and Screen Off mode.
- **YouTube Music-Style Expandable Persistent Queue**:
  - Dragging the thin white horizontal line at the bottom upwards smoothly expands the queue and moves playback controls to the top; dragging downwards collapses it.
  - Played and skipped songs remain visible in the queue rather than disappearing.
  - Tapping any song in the queue plays it immediately and marks intervening songs as skipped (`↷`) without deleting them.
  - Supports **Play Next** (priority insertion immediately after the current song, overriding shuffle), **Add to Queue** (appending to the end and incorporating into active shuffle), **Drag-and-Drop Reordering** of upcoming songs, and **Long-Press Contextual Actions** (`Add to Favorites`, `Add to Playlist`, `Remove from Queue`).
- **Separate Queue Order & Playback Order (Non-Destructive Shuffle)**:
  - Enabling shuffle randomizes only unplayed upcoming tracks once per cycle without interrupting the current song or altering the user's underlying `queueOrder`. Disabling shuffle immediately restores the manually arranged `queueOrder`.
- **Dark Mode & Light Mode**:
  - **Dark Mode (Default)**: Pure `#000000` background, `#FFFFFF` text/icons, subtle dark blue/purple (`#111626` / `#6B5B95`) frames and accents.
  - **Light Mode**: Clean `#FFFFFF` background, `#000000` text/icons, subtle light blue (`#EDF4FC` / `#2B6CB0`) frames and accents.
- **Completely Black Screen Mode with Double-Tap Wake**:
  - Activates a pure `#000000` full-screen overlay, hides status and navigation bars via `WindowInsetsController`, and sets window `screenBrightness = 0.0f`.
  - On the POCO X7 Pro's 6.67" CrystalRes AMOLED panel, `#000000` pixels turn off completely and emit zero visible light.
  - Wakes **only** on a verified double tap (60ms–380ms interval within 140px radius); single taps and accidental touches are ignored.

## Building the APK

### Option 1: Automatic Cloud Build via GitHub Actions (Included)
This repository includes `.github/workflows/build-apk.yml`. As soon as you push this repository to `https://github.com/NadavEpstein/music-player`:
1. Open the **Actions** tab on GitHub.
2. Select the **Build POCO X7 Pro Offline Music Player APK** workflow run.
3. Download **`poco-x7-pro-offline-music-player-debug-apk`** (`app-debug.apk`).

### Option 2: Local Build via Android Studio or Command Line
1. Install JDK 17 and Android SDK Platform 35.
2. Run:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
3. The installable APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Installing & Configuring on Xiaomi POCO X7 Pro (HyperOS 2)

1. Copy `app-debug.apk` to your POCO X7 Pro and tap it in the File Manager to install (allow *Install unknown apps* if prompted).
2. **First Launch — Granting Music Folder Access**:
   - On first launch, tap **Select Music Folder**.
   - The system picker opens at `Internal shared storage -> Music` (`/storage/emulated/0/Music`).
   - Tap **Use this folder** -> **Allow**. The app calls `takePersistableUriPermission` so you never need to select it again.
3. **HyperOS 2 Background Playback Optimization**:
   - For uninterrupted background playback during long sessions with the screen locked, open **Settings -> Apps -> Manage apps -> Music Player -> Battery saver** on your POCO X7 Pro and select **No restrictions**.

## Project Structure

```text
music-player/
├── .github/workflows/build-apk.yml
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/nadavepstein/musicplayer/
│       │   │   ├── data/
│       │   │   │   ├── AppPreferences.kt
│       │   │   │   ├── MusicDirectoryRepository.kt
│       │   │   │   └── StrictDirectoryPolicy.kt
│       │   │   ├── model/
│       │   │   │   └── AudioModels.kt
│       │   │   ├── queue/
│       │   │   │   └── QueueManager.kt
│       │   │   ├── service/
│       │   │   │   └── MusicPlaybackService.kt
│       │   │   ├── ui/
│       │   │   │   ├── LibraryAdapter.kt
│       │   │   │   ├── MainActivity.kt
│       │   │   │   ├── QueueAdapter.kt
│       │   │   │   └── ThemePalette.kt
│       │   │   └── util/
│       │   │       └── DoubleTapWakeDetector.kt
│       │   └── res/
│       │       ├── drawable/
│       │       ├── layout/
│       │       └── values/
│       └── test/java/com/nadavepstein/musicplayer/
│           ├── DoubleTapWakeDetectorTest.kt
│           ├── QueueAndShuffleEngineTest.kt
│           └── StrictDirectoryPolicyTest.kt
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```
