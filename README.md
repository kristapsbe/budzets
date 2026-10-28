# Budzets

An offline Android budgeting app. You log expenses, set daily, weekly or monthly targets
overall and per category, and close each month to turn unspent budget into surplus. That surplus
can be moved between categories or used to boost a later month. A home-screen widget shows the
month at a glance, and backups are files you save yourself to Google Drive or the phone.

- **Spec:** `PROJECT_PLAN.md`.
- **Decisions and deviations, phase by phase:** `IMPLEMENTATION_NOTES.md`. Read it before
  changing behaviour.

## Building

Requirements: Android Studio with its bundled JDK, and the Android SDK (compileSdk 37). The
project uses Gradle 9.8.1 and AGP 9.5.0, and supports Android 9 (API 28) and up.

Kotlin is 2.4.21: the `kotlin` version in `gradle/libs.versions.toml`. AGP 9 bundles an older
Kotlin, and the Compose and serialization plugins at that version lift the build to it.
`./gradlew buildEnvironment` shows the version in use.

```sh
# Command-line builds need a JDK; Android Studio's works:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # all unit and UI tests, on the JVM (Robolectric)
./gradlew lintDebug
./gradlew connectedDebugAndroidTest   # needs a device or emulator
```

### Release builds

Release builds are shrunk with R8. Keep rules live in `app/src/main/keepRules/`. Some problems
only show up in a release build, so always try one on a device; check the widget as well as the
app.

Signing reads a git-ignored `keystore.properties` in the project root. Without that file,
`assembleRelease` produces an unsigned APK.

```sh
keytool -genkeypair -keystore ~/keys/budzets.jks -alias budzets -keyalg RSA -keysize 4096 -validity 10000
```

```properties
# keystore.properties — never commit this file or the keystore
storeFile=/Users/you/keys/budzets.jks
storePassword=…
keyAlias=budzets
keyPassword=…
```

`./gradlew assembleRelease` then writes `app/build/outputs/apk/release/app-release.apk`. Keep the
keystore safe: an update can only be installed over the app if it is signed with the same key.

### Start-up performance

Judge speed on a release-like build, never on debug. A debuggable build runs without R8 and
without compiled code: it took about 2.3× as long to show Overview in our measurements, and it
stutters for the first seconds. In Android Studio, pick the `benchmarkRelease` variant in the
Build Variants panel. It is minified like release and signed with the debug key, so it installs
over a debug build and keeps its data. The debugger can't attach to it.

The app ships a Baseline Profile (`app/src/release/generated/baselineProfiles/`). It lists the
code start-up and the main screens use, so Android compiles that code ahead of time. Play applies
it at install time. A sideloaded APK gets it from the device's background compilation, usually
overnight, so its first launches are still uncompiled.

The `baselineprofile` module generates that profile and measures cold start. Both tasks need a
device or emulator with API 33 or later (or a rooted one):

```sh
./gradlew :app:generateBaselineProfile      # rewrites the two profile files; commit them
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=lv.kristapsbe.budzets.baselineprofile.StartupBenchmark
```

Regenerate the profile after larger UI changes. The journey it records
(`baselineprofile/.../Journeys.kt`) finds things by their labels, so update it when those change.
Results land in `baselineprofile/build/outputs/connected_android_test_additional_output/`. Emulator timings are
only good for comparing runs with each other. Both tasks uninstall the app afterwards, data
included; pass `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to keep it.

## Architecture

The app module, layered by package under `lv.kristapsbe.budzets`, plus `baselineprofile` (see
Start-up performance above):

| Package | What it holds |
|---|---|
| `domain` | Pure Kotlin rules with no Android code: money in minor units, budget planning (the plan is the highest monthly equivalent of the targets), pace, month close, and the append-only surplus ledger. |
| `data` | Room database (`local`), repositories exposing `Flow`s, DataStore settings, and `backup` (file format, validation, restore). |
| `ui` | Jetpack Compose screens, each with a ViewModel; navigation in `ui/navigation`. |
| `widget` | Glance widget, reading the same repositories as the app. |

`AppContainer` creates the dependencies; there is no DI framework. Every write goes through
Room, so the screens and the widget update from Room's invalidation without extra hooks.

## Data, backup and privacy

- **No account and no server.** The app has no internet permission. All data is on the phone.
- **Backup:** Settings → *Back up now* opens the system file picker. Choose Google Drive (the
  Drive app must be installed and signed in) or a folder on the phone. Each backup is a new dated
  JSON file containing all data and the currency.
  - Backup files are **not encrypted**. They hold your full spending history, so keep them as
    private as the phone.
- **Restore:** Settings → *Restore*. The file is checked completely (format, version, checksum,
  every reference) before anything changes.
  - Restoring **replaces** all data; it never merges.
  - The previous data is kept privately on the phone, so *Undo restore* can put it back until
    the next restore.
- **Reminder:** Overview reminds you to back up when you have data and no backup in 30 days.
- **Android's own backup:** Google's cloud backup carries nothing. A device-to-device transfer
  (setting up a new phone from the old one) carries the data over.

## Database changes and recovery

- The Room schema is exported to `app/schemas/`. Each schema change needs an explicit migration
  in `BudzetsDatabase.MIGRATIONS` and a test in `MigrationTest`.
- There is deliberately no destructive fallback. A missing migration makes the app fail to open
  rather than wipe data. The fix is to ship the migration; clearing app data would delete the
  user's records.
- If the backup format changes, raise `BackupCodec.SCHEMA_VERSION` and keep reading older files
  by upgrading them in `BackupCodec.decode`.
- **Recovery during development:** use the app's own backup (Settings), or copy the database
  from a debug build:

  ```sh
  for f in budzets.db budzets.db-wal budzets.db-shm; do
    adb exec-out run-as lv.kristapsbe.budzets cat databases/$f > $f
  done
  ```

  Copy all three files together. Recent changes may still be in the `-wal` file; SQLite
  merges them when it opens the copy.

## Known limitations

- **One currency.** Amounts are never converted. Once there is data, you can only switch to a
  currency with the same number of decimals.
- **No automatic backups.** With Drive, "Backup saved" means Drive accepted the file; it
  uploads it in the background.
- **Restore and currency:** a restore sets the currency just after replacing the data, not in
  the same transaction.
- **Widget:**
  - After *Force stop* in Android settings, the widget's buttons do nothing until the app is
    opened again, because Android cancels the app's pending actions.
  - A widget showing the moment midnight passes keeps yesterday's "days left" until the next
    change.
- **Language:** English only. All text is in `strings.xml`, ready for translation.
