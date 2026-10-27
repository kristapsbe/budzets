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

## Phase 3 — app shell and overview (done)

- **Dependencies added:**
  - Navigation Compose 2.9.8 with type-safe `@Serializable` routes
  - the Kotlin serialization plugin, pinned to the project Kotlin 2.2.10
  - kotlinx-serialization-core 1.9.0, the Kotlin 2.2 line (Navigation itself asks for
    1.7.3; it resolves to 1.9.0)
  - lifecycle-viewmodel-compose and lifecycle-runtime-compose 2.11.0
  - material-icons-core, version from the BOM (1.7.8)
  - Test-only: Compose ui-test-junit4, plus Espresso 3.7.0. ui-test-junit4 pulls 3.5.0,
    whose input injection fails on API 37 under Robolectric.
- **Navigation:** a bottom bar with Overview, Spending, Budgets, Surplus and Settings. Tabs
  keep and restore their state. The expense editor and the non-overview tabs are
  placeholders until their phases. Tapping a category opens `TransactionsRoute(categoryId)`.
- **Insets:** the outer Scaffold applies and consumes only the bottom bar's padding; each
  screen's own Scaffold/TopAppBar handles the top. Verified edge-to-edge on an API 37 emulator.
- **Overview:**
  - Content: month selector (plus "Go to current month"), remaining/spent card, base
    budget and surplus boosts on separate lines, pace status (icon + text, never colour
    alone) with the pace guide and days left, saved-surplus shortcut, category rows.
  - Categories active in the month are listed even without a budget. Archived categories
    with spending in the month are shown and flagged.
  - Banners: closed month, changed since closing, category plans above overall.
  - Empty states: no categories (offers starter categories), no budget (links to
    Budgets), no spending.
  - Loading and error-with-retry states.
- **Starter categories:** Groceries, Eating out, Transport, Home, Fun, Other. They come from
  string resources, so they're localisable, and are created only when no categories exist.
- **FAB:** uses the content-slot `ExtendedFloatingActionButton` overload. The animated
  `expanded` overload left the label out of the semantics tree, so TalkBack would have read
  only "Button". The FAB is hidden until a category exists.
- **Formatting:** `util/MoneyFormatter` is the single currency formatter (locale-aware,
  currency precision). Month names use the standalone form ("LLLL").
- **Robolectric UI tests** use `@GraphicsMode(NATIVE)`; legacy graphics mis-measures text.
- **Known gap (closed in Phase 4):** the selected month survived tab switches and configuration
  changes but not process death. It now lives in `SavedStateHandle`.
- Tests: 14 new (ViewModel 6, Compose UI 5, formatter 3); 87 unit tests total.
- **Manual check on emulator** (API 37), with budgets/expenses seeded via sqlite:
  - first launch, starter categories, populated overview (totals checked by hand)
  - over-budget row, month navigation, tab switch, dark theme
  - no crashes in the crash log

## Decisions from Phase 4 review (approved 2026-10-27)

- **Q7 — Amount input:** the system decimal keyboard, with the amount field focused on entry.
  No in-app keypad.
- **Q8 — Future dates:** refused. The picker stops at today and `ExpenseDraft.validate` rejects a
  later date. Repository `add`/`update` don't check this: tests and a future restore may need such
  rows.
- **Q9 — Preselected category:** the category of the most recently *added* expense (edits don't
  count), taken from the expense table, so nothing new is stored. A category passed in from a
  category's spending list wins. An archived category is never preselected.
- **Q10 — Spending tab:** one month at a time, with the same month selector as Overview, a total
  for the current filter, category chips and note search.

## Phase 4 — expense logging and history (done)

- **No new dependencies.**
- **Domain:** `domain/expense/ExpenseDraft.validate` reports every problem at once: amount
  (via `AmountParser`), missing category, future date.
- **Editor** (`ui/expenses/ExpenseEditor*`):
  - Amount field (locale symbol as prefix or suffix, locale decimal separator in placeholder and
    prefill), category chips, Today / Yesterday / date-picker chips, optional note.
  - Keyboard Done saves. The common path is FAB → amount → ✓.
  - Before the first save attempt only unfixable amount errors show (too many decimals, too
    large). After it, all errors show and update live.
  - Archived categories are hidden, except the one an old expense already uses.
  - A closed month that the save would change (the new date's or the original's) gets a hint to
    close it again (Q3).
  - Delete asks for confirmation.
- **State deviation:** the editor ViewModel exposes Compose snapshot state, not a `StateFlow`.
  Text-field values must update synchronously, or typing can drop characters and move the
  cursor. The form is mirrored into `SavedStateHandle`.
- **Date picker:** Material's picker uses UTC-midnight millis. Conversion is plain epoch-day
  arithmetic (`util/DateFormatting.kt`), never the device zone, so a picked date can't shift
  day or month. Tested across UTC+14 and UTC−11.
- **Spending** (`ui/expenses/Transactions*`):
  - Grouped by day with day totals (Today / Yesterday / weekday names).
  - Unfiltered, the month total equals Overview's spent; tests check it against `MonthSummary`.
  - Chips: categories active in the month, archived ones with spending in it, and always the
    selected one.
  - Swipe toward the start, or TalkBack's "Delete" custom action, deletes immediately with an Undo
    snackbar (10 s) that calls `ExpenseRepository.restore`.
  - The swipe state uses plain `remember`, not the saveable default. Otherwise an undone row
    would come back in its saved "dismissed" state and delete itself again.
- **Navigation:**
  - Tapping a category on Overview opens `CategorySpendingRoute(categoryId, month)`: a separate
    route with an up arrow and no bottom bar. Spending-tab arguments wouldn't work, because tab
    switches save and restore back stacks and would bring back a stale filter.
  - Close/back use `popBackStack<Route>(inclusive = true)`, so a double tap can't pop the screen
    underneath.
  - Month, category and query filters live in `SavedStateHandle`. Overview's month does too
    (null = follow the current month).
- **Widget:** saving updates Overview reactively. Widget refresh triggers come in Phase 7.
- **Found on the emulator and fixed:**
  - The keyboard stayed open over the date picker, hiding the lower weeks and OK.
  - Opening search didn't focus the field.
  - Both now have regression tests; the date-picker test was confirmed to fail without the fix.
- **Tests:**
  - 47 new (134 total): domain 5, formatting 3, repository 1, editor VM 12, Spending VM 7,
    editor UI 9, Spending UI 8, Overview VM 1, and 1 end-to-end flow.
  - The end-to-end flow runs the real activity, Room and DataStore under Robolectric: add from
    Overview → Overview updates → edit from Spending → delete + undo → last-used preselect + Done
    → drill-down and back. It was stable across 3 reruns.
  - `ui/ViewModelTest` is a shared base (Main dispatcher, temp DataStore). Its `awaitValue` result
    must be bound: as the last expression of `runTest`, T is inferred as `Unit`.
- **Manual check on emulator** (API 37, fresh install):
  - Add with picker (future days disabled); last-used + Done; Overview and Spending totals match
    (€22.70); swipe delete and undo; edit prefill.
  - The form, Overview month and Spending month survive activity and ViewModel destruction
    ("Don't keep activities").
  - Dark theme; no crashes in the crash log.

## Decisions from Phase 5 review (approved 2026-10-27)

- **Q11 — Effective month:** a budget change applies from the month shown in the Budgets tab
  (default: the current month) until a later scheduled change, which stays in force. Past
  and future months can be edited. In a closed month the editor shows the "close it again"
  hint (Q3).
- **Q12 — Category management:** lives in the Budgets tab: add, rename, archive and
  unarchive, move up and down, and delete only when the category has no history.
- **Q13 — Monthly target:** budgets can also take a monthly amount. The plan is the **highest**
  of the configured monthly equivalents (daily × N, weekly × N ÷ 7, monthly), never their sum.

## Phase 5 — budget management (done)

- **No new dependencies.**
- **Schema v2:**
  - First real migration: `MIGRATION_1_2` adds `budget_configs.monthlyTargetMinor` (nullable).
    It is registered in `BudzetsDatabase.build`, still with no destructive fallback.
  - `app/schemas/.../2.json` is committed.
  - `MigrationTest` builds a v1 database from the committed `1.json` and opens it with Room,
    which runs the migration and Room's own schema validation. Columns are also compared
    with `2.json`.
  - Room's `MigrationTestHelper` reads schemas only from instrumentation assets, which
    Robolectric can't see without shipping them in the debug APK.
  - A deliberately broken migration was confirmed to fail the test.
  - Also checked on the emulator: data created by the Phase 4 build plus v1 budget rows
    upgraded to v2 with everything intact.
- **Domain:**
  - `MonthlyPlanRule` handles three targets. `MonthlyPlan.determinedBy` is now a set of
    `TargetKind`, which lists every kind tied for the maximum. It replaces `PlanSource`.
  - `BudgetPlanning` adds three helpers:
    - `nextChangeAfter`
    - `previewWith`: resolves a month as if the draft were saved
    - `parseTargets`: empty = not set, 0 allowed, all empty = no budget from that month
- **Budgets tab:**
  - Month selector, plus a closed-month banner.
  - Overall card shows the plan, targets, "Since …" / "Changes in …", and an explanation
    when the overall is derived (Q2).
  - Warning when category budgets exceed the overall; otherwise the unassigned amount is
    shown (it becomes unallocated surplus).
  - Category rows with a ⋮ menu; an Archived section with Unarchive and Delete.
  - Delete asks for confirmation (its budgets go too) and is refused, with a message, for a
    category with history.
  - Archiving starts from the current month; earlier months keep the category.
- **Budget editor** (`BudgetEditorRoute(categoryId?, month)`):
  - Day, week and month fields; "Applies from … until …".
  - Live preview of each equivalent, with a check mark on the one that sets the plan and the
    "highest, not added" rule shown when more than one target is set.
  - "Clear all" means no budget from this month.
  - Save is disabled until the draft differs from the version in force, so no duplicate
    versions are stored.
  - Live warnings for categories above the overall and for closed months in the range.
  - Uses the same Compose-state + `SavedStateHandle` pattern as the expense editor.
- **Shared code:**
  - `ui/components/Banner` (moved from Overview)
  - `amountInputChars()` (moved from the expense editor)
- **Found on the emulator and fixed:** the rename dialog put the cursor before the existing
  name, so typing produced "LeisureFun". The name now opens fully selected. It has a
  regression test, confirmed to fail without the fix.
- **Also fixed:** a race in Phase 4's `ExpenseFlowTest`, which waited for the editor's Cancel
  button (present while still loading) instead of the form.
- **Tests:**
  - 39 new (173 total): plan rule 1, planning 4, repository 1, migration 1, Budgets VM 7,
    editor VM 9, Budgets UI 8, editor UI 7, and the `BudgetFlowTest` end-to-end flow.
  - `BudgetFlowTest`: overall daily → over-overall category → add category → Overview agrees.
    It was stable across 3 reruns.
- **Manual check on emulator** (API 37):
  - v1 → v2 upgrade with real data
  - Each target type, with the preview maths checked by hand (€12 × 31 = €372,
    €70 × 31 ÷ 7 = €310, €400 wins)
  - A November change shows "Changes in November 2026" in October; September is untouched
  - Add, rename, move, archive, and delete (both allowed and refused)
  - Editor draft and Budgets month survive "Don't keep activities"
  - Dark theme; no crashes
- **Phase 8 note:** the backup must include `monthlyTargetMinor`.

## Decisions from Phase 6 review (approved 2026-10-27)

- **Q14 — Which months can close:** any past month, plus the current month early. Expenses
  added after closing mark it "changed since closing", and closing again posts only the
  difference (Q3). Future months can't be closed.
- **Q15 — Reminder:** Overview (on the current month) shows a card for the most recent
  past month that had a budget and isn't closed, with a count of earlier ones. Months with no
  budget are skipped, since closing them would only turn spending into debits.

## Phase 6 — surplus ledger and month close (done)

- **No new dependencies, no schema change.** The Phase 1–2 domain and repository did the
  accounting; this phase adds the screens plus a few pure helpers.
- **Domain:**
  - `CloseReview` (via `MonthFinalizer.review`): per pocket, what closing should leave
    (`target`), what is already recorded (`recorded`), and the difference (`pending`).
    `MonthFinalizer` now uses the same `closureChanges`, so the review and the actual close
    can't disagree.
  - `SurplusLedger.breakdown`: per pocket, from closing / moved / reserved for boosts, summing
    to the available balance (spec §7.5's "available vs transferred vs allocated").
  - `ClosingSchedule.unclosedBudgetedPastMonths` drives the Q15 reminder.
  - `MonthFinalizer.REOPEN_NOTE` marks reopen entries so history can tell them from re-closes.
- **Repository:** `SurplusRepository.observeCloseState(month)` gives the live review plus the
  latest closure record.
- **Month review** (`MonthCloseRoute`):
  - One line per pocket with budget, boost and spent. Spending in a category without a budget
    is stated as "recorded as a debit", the point promised for this phase.
  - Unallocated line, net total, and a "not a bank balance" note.
  - Open → "Close month". Changed → shows only the differences and "Close again". Closed →
    "Reopen month" after a confirmation.
  - A refused reopen names each pocket and the negative balance it would be left with.
  - Notes for an early close of the current month and for a month with no budget.
- **Overview:** the Q15 reminder. Closed and changed banners now carry an action (View,
  Review & close); a past open month gets a "not closed yet" prompt; the current open month
  has a quiet "Review & close this month" link.
- **Surplus tab:**
  - Total, then pockets in category order with Unallocated last. Each shows its breakdown;
    a negative pocket shows "−€x debit" plus how it's repaid.
  - Active boosts with Release (not for closed months).
  - History newest first, filterable by pocket. Closing entries name their source month,
    transfers the other pocket, boosts the boosted category and month.
- **Move surplus / Boost a budget:**
  - Pocket chips showing balances, an "All (€x)" shortcut, live "Only €x is available", and an
    optional note.
  - Boost months start at the current month; a closed month is refused with a banner. A
    preview shows "Budget for X in Month: before + boost = after".
  - Defaults: the passed-in or only pocket with surplus; for a boost, the destination's own
    pocket.
  - Same Compose-state + `SavedStateHandle` pattern as the other editors.
- **Shared:** `ui/components/FormParts` (`FormSection`, `amountErrorText`), replacing three
  copies; `Banner` gained an optional action.
- **Found on the emulator and fixed:**
  - The reopen-refused message lost its line breaks: literal newlines in a string resource
    collapse to spaces, so it now uses `\n`.
  - Negative amounts used a hyphen.
  - The boost preview's "Groceries’s budget" was rephrased.
- **Tests:**
  - 28 new (201 total): domain 3, repository 1, month review VM 4, surplus VM 4, move/boost
    VM 6, overview VM 1, review UI 4, surplus/boost/move/reminder UI 4, and `SurplusFlowTest`.
  - `SurplusFlowTest`: set budgets → close this month early from Overview → move → boost next
    month → Overview shows the boost → a late expense → close again. It was stable across 3
    reruns, as were the other two flows.
- **Manual check on emulator** (API 37):
  - September budget and expenses set up through the UI → Overview reminder → review (+€50
    Groceries, −€40 debit for unbudgeted Eating out, +€400 unallocated, net +€410) → close.
  - Surplus breakdowns; move €40 to repay the debit; boost November €30
    (preview €200 + €30 = €230).
  - Reopen refused, listing the pockets; release boost; refused again for Unallocated only.
  - Move form survives "Don't keep activities"; dark theme; no crashes.

## Open questions

None currently.
