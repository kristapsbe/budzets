# CLAUDE.md

Budzets is an offline-first Android budgeting app (Kotlin, Jetpack Compose, Room, Glance widget),
package `lv.kristapsbe.budzets`. It has no account, no server and no internet permission.

Read these before changing behaviour:
- `PROJECT_PLAN.md`: the original spec. Its §18 holds the working rules below.
- `IMPLEMENTATION_NOTES.md`: every decision and deviation, phase by phase and feature by feature,
  with the numbered product questions (Q1–Q49 so far), what was verified, and an **Open questions**
  section at the end.
- `README.md`: build, release, architecture and user-visible behaviour.

## Working rules

- **Never commit or push to `main`.**
  - Branch from an up-to-date `main` (`git switch main && git pull && git switch -c feature/<name>`)
    and push that branch.
  - Merging and opening a PR are the owner's call unless they ask.
  - Commit and push only when asked.
- **Stage files explicitly.**
  - Android Studio rewrites some tracked `.idea` files (`gradle.xml`, `misc.xml`,
    `inspectionProfiles/`). Leave those changes out of commits.
  - Never commit `keystore.properties`, a keystore, or `app/release/`. All three are git-ignored.
- **One change at a time.**
  - Inspect, state the approach, make focused changes, then stop and report real results: test
    counts, lint, and what was checked on a device.
  - Never claim a build or test passed without running it and seeing it pass.
  - Avoid unrelated refactors and new dependencies.
- **Ask before building product behaviour that isn't settled.**
  - Put the question with a recommended option first.
  - Record the answer in `IMPLEMENTATION_NOTES.md` as the next Q number (next: **Q57**).
  - Mark any answer it supersedes.
- **Write up each change in `IMPLEMENTATION_NOTES.md`.**
  - Add a dated section with the decisions, deviations and verification.
  - Update `README.md` and `PROJECT_PLAN.md` only where they describe what changed.
- **Dependencies must be at least two weeks old.**
  - Check the real release date, e.g. the POM's `Last-Modified` on Maven.
  - Keep them compatible with the project's Kotlin.
  - Versions live in `gradle/libs.versions.toml`. Record upgrades in the notes.
- **Don't change these without asking:**
  - the reopen refusal rule (see Open items)
  - the money and ledger model

## Setup and commands

Needs Android Studio with its bundled JDK and the Android SDK (compileSdk/targetSdk 37, minSdk 28).
The Gradle daemon JVM toolchain is resolved by foojay (`gradle/gradle-daemon-jvm.properties`). The
configuration cache is on.

```sh
# There's no JDK on PATH by default; use Android Studio's (macOS path shown):
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug
./gradlew testDebugUnitTest            # all unit + Compose UI tests, on the JVM (Robolectric)
./gradlew testDebugUnitTest --rerun    # force a full re-run
./gradlew testDebugUnitTest --tests 'lv.kristapsbe.budzets.ui.surplus.*'
./gradlew lintDebug                    # must stay at 0 errors
./gradlew connectedDebugAndroidTest    # needs a device/emulator
./gradlew assembleRelease              # R8-optimized; signed only if keystore.properties exists

# Count results after a run:
f=app/build/test-results/testDebugUnitTest
echo "tests $(grep -ho '<testsuite [^>]*' $f/*.xml | grep -o ' tests="[0-9]*"' | tr -dc '0-9\n' | paste -sd+ - | bc)" \
     "failed $(grep -ho '<testsuite [^>]*' $f/*.xml | grep -o ' failures="[0-9]*"\| errors="[0-9]*"' | tr -dc '0-9\n' | paste -sd+ - | bc)"
```

Run the full suite **three times** before reporting a change as done; the last change ended at 556
tests.

Known flakes:
- `WidgetStateFlowTest` is occasionally €0.50 off. It is pre-existing and passes alone.
- "Dispatchers.Main is used concurrently with setting it" is a ViewModel scope racing `resetMain`.
  To avoid it, wrap every ViewModel a test creates in `.clearedAfterTest()` (`ui/ViewModelTest.kt`).

## Architecture

| Package | What it holds |
|---|---|
| `domain` | Pure Kotlin with no Android code: `Money` in minor units, budget planning (the plan is the highest monthly equivalent of the targets), pace, the daily amount, month close (`MonthFinalizer`), the append-only surplus ledger (`domain/surplus`), and spending forecasts (`domain/forecast`). |
| `data` | Room (`local`, DB version 3), repositories exposing `Flow`s, DataStore settings (`SettingsRepository`), and `backup`. |
| `ui` | Compose screens, each with a ViewModel; navigation in `ui/navigation/BudzetsApp.kt`. |
| `widget` | Glance widget, reading the same repositories; `QuickAddActivity` is the widget's "+". |

Dependency wiring:
- `AppContainer` builds the dependencies by hand; there is no DI framework.
- Every write goes through Room, so the screens and the widget update from Room invalidation.
- ViewModels read state from repositories; form state lives in `SavedStateHandle`.

### Model rules

- **Surplus is a ledger of pockets.** There is one pocket per category, plus Unallocated (a null
  category id). Entries are never edited. Moves are linked `TRANSFER_OUT`/`TRANSFER_IN` pairs.
- **Debt is a pocket below zero.**
  - Saved surplus and debt are always shown apart, never netted.
  - Debt comes out of the current month's figures while that month is open and has a budget
    (`MonthSummaryCalculator.withDebt`).
  - `closingEffect` uses `remaining`, so don't subtract debt a second time.
- **Closed months are frozen.** They read their budgets from the close snapshot, not live configs.
  Spending stays live, so editing an expense in a closed month flags "changed since closing".
- **Deleted categories:**
  - A category with history keeps a hidden row (`deletedAt`), because references are RESTRICT.
  - Its pocket merges into Unallocated only when balances are read (`SurplusLedger.merged`).
  - Don't replace that with a transfer: reopening the month would then be refused forever.
- **Pay off / Add surplus** draw from all saved surplus: Unallocated first, then the fullest
  pockets (`SurplusDraw`, `SurplusOperations.transferFromAll`).
- **A category with no amount** counts as zero in a month that has a budget.

### Changing persisted formats

- **Database:**
  - Bump `BudzetsDatabase.VERSION`, commit the exported schema in `app/schemas/`, add the migration
    to `BudzetsDatabase.MIGRATIONS`, and test it in `MigrationTest`.
  - There is deliberately no destructive fallback.
- **Backup file:**
  - Raise `BackupCodec.SCHEMA_VERSION` (now 3) and document it in `BackupFormat.kt`.
  - New fields need defaults, so older files still decode. Newer files are refused.
  - A restore replaces everything, so an old file clears new settings.
  - Update `BackupCodecTest` and `BackupRepositoryTest`.
- **Settings:**
  - DataStore keys live in the `SettingsRepository` companion.
  - Flows use `distinctUntilChanged`, so writes don't wake the widget needlessly.
- **Android's own backup** (`res/xml/data_extraction_rules.xml` and `backup_rules.xml`):
  - Cloud backup carries nothing (Q6).
  - Device-to-device transfer carries the database and settings (Q23), but not WorkManager's
    database.
  - `noBackupFilesDir`, which holds the pre-restore safety copy, is never transferred.
  - Keep both files in step.

### Gotchas already paid for

- Kotlin's `combine` takes at most 5 flows typed; nest a `Pair`/`Triple` combine instead of going
  past it.
- `stringResource` can't be called inside non-inline lambdas such as `joinToString { }`. Use
  `map { … }.joinToString()`.
- Gestures: anything that claims a drag must consume every change until lift (see
  `ui/components/SwipeToDelete.kt`). Compose 1.12's parent drag otherwise takes the gesture back.
  Don't put `horizontalDrag` back.
- Navigation fades are app-wide on the `NavHost` (`navFade`, 500 ms, in `BudzetsApp.kt`).
- Widget:
  - `SizeMode.Exact`, with text measured by `WidgetText` (`TextPaint`).
  - Language changes need the `LOCALE_CHANGED` receiver.
  - Midnight rollover uses an inexact alarm (`WidgetSchedule`); there are no wakeup alarms.
  - After a Force stop the widget is inert until the app opens.
- R8 full mode also optimizes the app's own code. Keep rules are in `app/src/main/keepRules/`;
  WorkManager's `InputMerger` needed one, or the widget never rendered. Always try a release build
  on a device, widget included.
- Locales: `[de, lv]` on a device gives English, not Latvian, because library resources make German
  "supported".

## Strings and Latvian

- Every user-visible string goes in `res/values/strings.xml` **and** `res/values-lv/strings.xml`.
- `LatvianResourcesTest` checks that the placeholders match.
- The Latvian translation was written by Claude and has **not yet been read by a native speaker**.
  Flag new Latvian strings in the notes.
- The app follows the device language. There is no in-app picker (Q26).

## Baseline Profiles

- The profiles are committed in `app/src/release/generated/baselineProfiles/` (`baseline-prof.txt`
  and `startup-prof.txt`) and used at build time.
- When a profiled class's signature changes, either hand-edit both files, or regenerate them with
  `./gradlew :app:generateBaselineProfile` (needs a device or emulator on API 33+).
  - Hand-editing: take the new descriptors from `javap -p -s` on the compiled classes under
    `app/build/intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`.
  - Regenerating: then check the rule count and look for obfuscated names, which mean the
    `nonMinifiedRelease` fix in `app/build.gradle.kts` broke.
- The journey in `baselineprofile/.../Journeys.kt` finds things by label, so update it when labels
  change.
- Macrobenchmark and generation uninstall the app afterwards. Pass
  `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to keep it installed.
- Judge speed on `benchmarkRelease`, never on debug.

## Checking on an emulator

The AVD used so far is `Medium_Phone_API_37.0`.

1. **Check whether an emulator is already running:** `ps -axo pid,lstart,command | grep qemu-system`.
   - A windowed one (`-qt-hide-window`) belongs to the owner. Don't drive, reboot or stop it.
     Use a throwaway AVD instead: set `ANDROID_AVD_HOME` to a temp directory, start it with
     `-port 5556`, pass `-s emulator-5556` to every adb call, and delete it afterwards.
2. **Start headless and read-only**, so nothing on the AVD changes:
   `emulator -avd Medium_Phone_API_37.0 -no-window -read-only` (add `-gpu host` for frame timings).
3. **Always stop it when done:** run `adb -s emulator-5554 emu kill`, then confirm there is no
   `qemu-system` process, `adb devices` is empty, and there's no
   `~/.android/avd/Medium_Phone.avd/hardware-qemu.ini.lock`. A leftover headless emulator locks the
   AVD, and Android Studio can't start it.

Seeding data:
- Write a temporary Robolectric test with its own clock that builds data through the repositories
  and writes `BackupRepository.export()` to a file.
- Push the file to `/sdcard/Download/` and send the `MEDIA_SCANNER_SCAN_FILE` broadcast.
- Restore it through Settings → Restore → Downloads, then **delete the temporary test**.
- Release builds can't use `run-as`; restore a backup there the same way.

Other tricks:
- `pm clear lv.kristapsbe.budzets` gives a clean install.
- `cmd locale set-app-locales lv.kristapsbe.budzets --locales lv-LV` switches the app and widget to
  Latvian.
- Also check dark mode and font scale 1.5.
- For state restore, launch with `monkey -p lv.kristapsbe.budzets -c android.intent.category.LAUNCHER 1`.
  A task started with `am start -n` isn't resumed by the launcher. Also try "Don't keep activities"
  (`settings put global always_finish_activities 1`, then set it back to 0).
- Widget:
  - Add it with long-press home → Widgets → "budzets" → `input draganddrop`.
  - Resize it by long-pressing, then `input swipe` on a handle.
  - It has to be re-added after reinstalling with a different signature.
- To test a DB upgrade, build the previous commit's APK in a temporary `git worktree`, install it,
  then install the new one.

Release smoke test:
- Sign with a throwaway keystore and a temporary `keystore.properties`, both outside the repo or
  deleted afterwards.
- Uninstall the debug build first, since the signatures differ.

## Open items

- **Reopen rule (open question):** `MonthFinalizer.reopen` refuses to reopen a month whose close paid
  off a debt, and its message wrongly says surplus was moved. A fix is proposed in the notes
  ("Debt, and amounts that aren't set"). Don't change it until the owner decides.
- **Not checked by the owner yet:**
  - Google Drive backup and restore on a real phone (the AVD has no Google account)
  - the Latvian translation
  - gesture feel and TalkBack on a real phone
- **Per-category forecasts (Q56):** built so the owner can judge them; they may go back to total-only
  bars. The model's constants are in `ForecastRules`.
- **Possibly revisited later:** the widget's `setWindow` 15-minute alarm window has no measured
  gain on API 37 and may be dropped.
