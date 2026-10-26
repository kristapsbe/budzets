# Implementation Notes

Companion to `PROJECT_PLAN.md`. Records decisions, deviations, and open
questions. Keep short; update as phases complete.

## Phase 0 — baseline (2026-10-26)

### Project as found

| Item | Value |
|---|---|
| Modules | single `:app` |
| Package / applicationId | `lv.kristapsbe.budzets` (kept) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 28 |
| AGP | 9.4.1, using AGP's **built-in Kotlin** (no `kotlin-android` plugin) |
| Kotlin | 2.2.10 (resolved KGP; `kotlin.compose` plugin pinned to `kotlin` version) |
| Gradle | 9.6.0 wrapper, daemon JVM toolchain 25, configuration cache on |
| Java source/target | 11 |
| Compose | BOM 2026.02.01, Material 3, activity-compose 1.13.0, lifecycle 2.11.0 |
| Code | template `MainActivity` (Greeting), `ui/theme` (dynamic color, light/dark) |
| Tests | template `ExampleUnitTest`, `ExampleInstrumentedTest` |
| R8 | AGP 9 `optimization {}` DSL; keep rules in `src/main/keepRules/` |
| Android backup | `allowBackup="true"`, backup/extraction rules are empty templates |

Baseline: `./gradlew assembleDebug testDebugUnitTest` → **BUILD SUCCESSFUL**.

Environment note: no JDK on `PATH`. Builds from the CLI need
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
AVD `Medium_Phone_API_37.0` is available for instrumented tests.

### Decisions

- **Single module**, layered by package (`data`, `domain`, `ui`, `widget`).
  The `domain` package must not import `android.*`, `androidx.*`, or Room.
- **No DI framework.** Manual `AppContainer` owned by a custom `Application`.
- **java.time natively** (minSdk 28), no core-library desugaring needed.
- **Money:** `Long` minor units wrapped in a value class. Fraction digits come
  from `java.util.Currency`.
- **Weekly extrapolation rounding:** compute `W × N` exactly, divide by 7
  once, round HALF_UP to minor units. Daily+weekly → max of the two monthly
  equivalents (one function, `domain/calculation/MonthlyPlanRule.kt`).
- **Drive backup via Storage Access Framework** (`CreateDocument` /
  `OpenDocument`). Google Drive appears as a provider in the system picker.
  The same code path covers local export. No OAuth, no Cloud Console, and no
  Play Services dependency. A Drive REST API (`drive.file`) upgrade path is
  documented below if it is ever needed.
- **Android Auto Backup:** DB and DataStore excluded (Q6, Phase 2).

### Dependencies to add (in the phase that first needs them)

| Phase | Dependency | Why |
|---|---|---|
| 1 | `kotlinx-coroutines-test` (test) | Flow/coroutine tests |
| 2 | Room (`runtime`, `ktx`, `compiler` via **KSP**), KSP plugin | persistence |
| 2 | `datastore-preferences` | currency, last-backup status |
| 2 | `room-testing`, Robolectric *or* instrumented tests | DAO/transaction tests |
| 3 | `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` | VM + `collectAsStateWithLifecycle` |
| 3 | `navigation-compose` | type-safe routes (uses kotlinx-serialization) |
| 3 | `material-icons-core` (if needed) | M3 no longer pulls icons in transitively |
| 7 | `glance-appwidget`, `glance-material3` | widget |
| 8 | `kotlinx-serialization-json` + serialization plugin (KGP 2.2.10) | backup JSON |

Versions get picked at implementation time, and each must be compatible with
AGP 9 built-in Kotlin 2.2.10. KSP must be a KSP2 release that supports
AGP built-in Kotlin.

### Drive integration: options considered

1. **SAF document picker (chosen).** User picks a location (Drive, local,
   other provider). The file is visible and manageable in Drive. Limits: Drive
   must be installed/signed in on the device. Drive uploads asynchronously
   after the provider accepts the write, so "success" means the provider
   accepted the bytes. A persisted URI permission allows "back up again to
   the same file".
2. **Drive REST API, `drive.file` scope.** Needs a Google Cloud project, the
   Drive API enabled, an OAuth consent screen, Android OAuth clients
   (package + SHA-1 for debug and release keys), and Play Services
   `AuthorizationClient` plus an HTTP client. Benefit: confirmed upload and
   possible future scheduled backups.
3. **`drive.appdata`.** Hidden from the user and wiped if the app is
   uninstalled, which conflicts with "a file the user can find/manage". Rejected.

## Decisions from Phase 0 review (approved 2026-10-26)

- **Q1 — Overspend (option c):** at month close, a category's negative remaining is
  posted as a `FINALIZE_DEBIT` to that category's own surplus pocket. **Balances may go
  negative.** A negative pocket is repaid by later surpluses or by transfers in. Transfers
  and boosts *from* a pocket are refused if they exceed its balance, so a negative pocket
  can't send anything.
- **Q2 — No overall budget:** the overall plan = sum of category plans, flagged
  `overallDerived`. A derived overall never warns and has no unallocated surplus.
- **Q3 — Changes in a closed month:** allowed. `MonthFinalizer.hasChangesSinceClosing`
  detects drift, and closing again posts only per-pocket deltas (`ADJUSTMENT_*`) with
  `revision + 1`. Reopen posts reversing adjustments. It is refused if any pocket would end
  below zero (surplus already moved or reserved).
- **Q4 — Boosts:** one boost = one source pocket (category or Unallocated). Allowed for the
  current month and future months that aren't closed. Unspent boost money is part of the
  destination category's remaining, so it becomes that category's surplus at close.
- **Q5 — Currency:** default **EUR**, not the device locale. Once data exists, switching
  is only allowed between currencies with equal fraction digits; amounts are never converted.
- **Q6 — Auto Backup:** exclude the database and DataStore (done in Phase 2).

## Phase 1 — domain layer (done)

Pure Kotlin under `domain/` (`model`, `calculation`, `surplus`). No Android, Room or
coroutines. The persistence layer will commit each `Outcome.Ok` record set in one Room transaction.

- **Rounding:**
  - Weekly → monthly is `(W·N + 3) / 7` in integer minor units, which is half-up. A .5 tie
    can't happen when dividing by 7.
  - The pace guide is rounded half-up too.
  - No intermediate rounding.
- **Overflow:** `Money` arithmetic uses `Math.*Exact` and throws on overflow.
  `AmountParser` caps input at 1e11 minor units.
- **Amount input:** `,` or `.` as the decimal separator. Grouping separators and exponents
  are rejected, and so is precision beyond the currency's digits. Zero is allowed only for
  budget targets (`allowZero`).
- **Zero vs unset:** a target of `0` is a real zero plan; `null` means not configured.
- **Archived categories** leave plans from their `archivedFrom` month. Their expenses still
  appear in summaries.
- **Pace status:** `NO_BUDGET` if effective ≤ 0; `OVER` if remaining < 0; `WATCH` if spent >
  110% of the pace guide; otherwise `ON_TRACK`. Today counts as elapsed.
- **Closing:** the current month may be closed early (later expenses → re-close); future
  months may not. Closure records keep a `MonthSummary` snapshot.
- **Unallocated surplus** = max(0, overall base − Σ category bases). Boosts are excluded
  because they raise both sides equally.
- Spec deviation: a boost's funding is linked to a source **pocket**, not to a specific
  credit entry (`sourceSurplusEntryId` in spec §10). Surplus within a pocket is fungible.
- Tests: 51 domain unit tests (`app/src/test/.../domain/`).

## Phase 2 — local persistence (done)

- **Dependencies added:** Room 2.8.5 (KSP 2.3.12, Room Gradle plugin) and DataStore
  Preferences 1.2.1. 1.3.0 was skipped because it was released only days earlier.
  Test-only: Robolectric 4.17, room-testing, kotlinx-coroutines-test 1.10.2, and
  androidx.test core-ktx 1.7.0.
- **Robolectric** runs Room tests on the JVM (`./gradlew testDebugUnitTest`). Its API 36+
  runtime needs `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED`, which is set in
  `app/build.gradle.kts`.
- **Schema:** version 1, exported to `app/schemas/`; commit it. There is deliberately **no
  destructive-migration fallback**. Future schema changes need an explicit `Migration`
  plus a `MigrationTestHelper` test against the exported JSON.
- **Storage conventions:**
  - money is `Long` minor units
  - months are `YYYY-MM`, dates are ISO `YYYY-MM-DD` (local), instants are epoch millis
  - a null category on surplus rows = the Unallocated pocket
  - Month queries use the half-open ISO date range `[YYYY-MM-01, next-01)`.
- **Budget versions:** unique on `(scopeKey, effectiveFrom)`. `scopeKey` = `@overall` or
  the category id, so the overall budget is covered too (SQLite treats NULLs as distinct).
  Saving the same month updates that version in place.
- **Categories:**
  - Archived via `archivedFrom` month, not a boolean.
  - Every reference is FK `RESTRICT`, except budget configs (`CASCADE`).
  - `deleteIfUnused` deletes only categories with no expenses, ledger, boosts or snapshots.
  - Names are trimmed, 1–40 chars, and not required to be unique.
- **Atomicity:** every surplus operation reads the ledger, plans in the domain layer, and
  writes in one `withTransaction`. Tests cover a forced mid-transfer failure (no partial
  rows) and 5 concurrent closes of the same month (exactly one succeeds).
- **Closures:** one row per (month, revision) plus a normalized per-category snapshot
  table. Only `reopenedAt` is ever updated.
- **Settings (DataStore):** `currency_code` (default EUR) and `last_backup_at`.
  `setCurrency` enforces the Q5 rule. "Has data" = any expense, budget config or
  ledger row.
- **Clock:** `AppContainer` uses a clock that reads the device's *current* zone on every
  call, so "today" stays correct after travel. Tests use a fixed Europe/Riga clock.
- **Auto Backup (Q6):** `data_extraction_rules.xml` (API 31+) and `backup_rules.xml`
  (API 28–30) exclude the `database` domain and `datastore/`. The exclusion also covers
  **device-to-device transfer**, for consistency with "explicit backup only". The
  explicit backup (Phase 8) is the migration path.
- **Not done here:** no starter categories are seeded; the Phase 3 setup flow decides. No
  migration tests exist yet, because there is only schema v1.
- Tests: 21 persistence tests (`app/src/test/.../data/`), plus the 51 domain tests.

## Open questions

None currently.
