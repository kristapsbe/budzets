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

## Decisions from Phase 7 review (approved 2026-10-27)

- **Q16 — What the widget shows** beyond the spec's minimum (month, spent, effective budget,
  remaining, bar): pace status in words with days left, the category furthest over budget, and
  saved surplus. The extras only appear at sizes with room for them.
- **Q17 — Quick add:** a "+" button on the widget opens the app straight at Add expense.

## Phase 7 — home-screen widget (done)

- **Glance 1.2.0** (`glance-appwidget`, plus `glance-appwidget-testing` for tests). The widget
  is written in the same Compose style as the app, instead of hand-built `RemoteViews`.
  `GlanceTheme` gives dynamic colours and dark mode. No schema change.
- **Layouts** (`SizeMode.Responsive`, default 3×2, resizable):
  - Small (2×1): remaining (or "spent" with no budget) and the bar.
  - Medium: adds the month, the "+" button, "€x of €y spent", and status · days left (icon and
    words, never colour alone).
  - Large (4×2 and up): adds the most over-budget category and saved surplus.
- **Same source of truth:** `WidgetStates.observe` combines the repositories' flows (month
  summary, categories, surplus balances, currency) into a `WidgetState`. `WidgetStates.build`
  does the mapping and is tested on its own.
  - Only categories with a budget or a boost count as "over". With no budget, spending is
    shown as plain spending: a full bar in the normal colour, not red (`isOver`).
- **Refresh triggers:**
  - `WidgetRefresher` watches Room's invalidation tracker for every table the widget reads,
    plus the currency in DataStore, debounced 500 ms. Every write the app makes goes through
    Room, so expenses, budgets, categories, transfers, boosts, closes and the Phase 8 restore
    are all covered without hooks in each screen. It runs only while a widget exists: started
    on app launch if one does, and from `onUpdate` when the first one is added.
  - `WidgetSchedule`: one inexact, non-waking alarm just after local midnight, so days left and
    the month roll over. Rescheduled on every refresh.
  - The daily `updatePeriodMillis` and the update Android sends after a reboot are fallbacks.
- **Missing data:** no categories → "Open Budzets to set up categories and a budget."; no
  budget → spending only, "No budget set".
- **Quick add:** the widget opens `MainActivity` with `ACTION_ADD_EXPENSE`. The activity is
  `singleTop`, so a running app gets `onNewIntent` and Add expense opens on top of where it was
  (an editor already open is kept). A recreated activity doesn't replay the request.
- **Found on the emulator and fixed:**
  - The widget didn't change after an expense was added while its Glance session was still
    alive. `provideGlance` loaded one snapshot before `provideContent`, and an update to a live
    session only recomposes it, so it showed the old snapshot. The content now collects
    `WidgetStates.observe` inside `provideContent`. The first value is loaded up front so the
    first frame has data.
  - With no budget, any spending showed a full red bar, as if over budget.
- **Known limits:**
  - "Today" is fixed for a session. A session alive at midnight shows yesterday until the next
    change; sessions last seconds, so this is rare.
  - Q18 below: after an app update, the launcher icon can reopen Add expense.
- **Tests:**
  - 14 new (215 total): state mapping and midnight schedule 5, content at each size 5,
    refresher 1, live state flow 1, quick add 2.
  - `WidgetStateFlowTest` keeps one collection open, as a session does. Setup, an expense, a
    budget and a currency change must each arrive through it.
- **Manual check on emulator** (API 37, widget at 4×2):
  - Quick add from the widget → €12.50 → home shows it; a second quick add within seconds →
    €19.50 (the bug above).
  - Edit €7 → €27 (€39.50), delete (€12.50) and a €10 Groceries budget ("Over by €2.50",
    "Groceries over by €2.50") all reached the widget.
  - Light and dark theme. Small and medium sizes are covered by the content tests, not
    resized by hand.

## Decisions from Phase 8 review (approved 2026-10-28)

- **Q19 — Settings:** the full §7.6 screen, not only backup: backup and restore, last-backup
  status, a currency picker (the Q5 equal-decimals rule), how budgets work, privacy, and
  versions.
- **Q20 — Backup destination:** a new dated file each time (`budzets-backup-YYYY-MM-DD.json`,
  and the picker adds " (1)" if the name is taken). Earlier backups are never overwritten.
- **Q21 — Restore safety:** a confirmation stating that data is replaced, plus an automatic
  private copy of the current data, with "Undo restore" until the next restore.
- **Q22 — Fallback:** the save picker alone. It already offers the phone's own storage, and
  restore reads from there too. No separate Share action.

## Phase 8 — backup and restore (done)

- **Dependency:** `kotlinx-serialization-json` 1.9.0, the same version as the
  `kotlinx-serialization-core` already used by navigation, and compatible with Kotlin 2.2.10.
  No schema change; the new `BackupDao` only adds queries.
- **Drive route:** the Storage Access Framework, as chosen in Phase 0, with no Google sign-in
  and no Cloud project. Drive appears in the system picker when its app is installed and
  signed in. A successful write means Drive accepted the file; it uploads it afterwards.
- **File format** (`data/backup/BackupFormat.kt`, version 1): a JSON envelope with `format`,
  `schemaVersion` (the format's own version, not the database's), `appVersion`, `createdAt`,
  `checksum` and `data`. `data` holds the settings (currency), categories, budget versions,
  expenses, the ledger, boosts, and month closures with their category snapshots.
  - Instants are ISO-8601; other values follow the database conventions. Pretty-printed, so the
    file can be read by hand.
  - Rows are in insertion order, so ties in "newest first" lists survive a restore.
  - The checksum is SHA-256 of `data` in compact form, so re-indenting is fine but any changed
    value is caught.
  - Never stored: the last-backup time, the restore marker, and any credential or device id.
- **Reading a file** (`BackupCodec.decode`, `BackupValidator`): nothing is touched until it
  passes every check.
  - Not JSON, or no format marker → "not a backup". A file cut short is "damaged".
  - A newer `schemaVersion` → "update the app". Version 0 or a missing version → damaged.
  - Checksum mismatch, missing or unknown fields → damaged.
  - Content checks: unique ids and keys; every reference resolves; dates, months and instants
    parse; amounts are within the domain's limits (positive ledger and boost amounts, expenses
    up to `AmountParser.MAX_MINOR`); enum values are known; the currency exists.
  - Files over 64 MB are refused.
  - Older formats: only version 1 exists. The decoder has a marked place where a later version
    upgrades an older `data` before decoding. Optional fields missing from a file read as null.
- **Restore** (`BackupRepository`):
  - Export the current data to `noBackupFilesDir/pre-restore-backup.json` (an atomic write,
    never uploaded by Android).
  - Then, in one Room transaction, clear every table (children first) and insert the backup
    (parents first). Any failure rolls back; a failure writing the safety copy stops before
    anything changes.
  - Then set the backup's currency, bypassing the equal-decimals rule since all amounts were
    replaced, and record the restore time, which turns on Undo.
  - Undo restores the safety copy the same way, then deletes it and clears the marker.
  - Known limit: the currency lives in DataStore, outside the Room transaction. If that one
    write failed after the data was committed, the old currency would remain.
- **Screens and widget** update through the existing flows and Room invalidation; the widget
  needed no hook (checked in `WidgetRefresherTest`). Open editors in other tabs whose record is
  gone after a restore show their existing "not found" state.
- **Settings screen** (`ui/settings`):
  - The backup card: what backup does, the last backup time (on this phone), "Back up now" and
    "Restore", and a progress line while busy.
  - After a restore, a banner with "Undo restore", which asks first.
  - The restore confirmation shows the backup's date, its counts (categories, expenses, closed
    months), the expense date range and currency, and the replacement warning.
  - Currency: a searchable list of currencies in use today, without historic ones.
    Different-decimals currencies are disabled with "N decimals" once amounts exist.
  - Static sections: how budgets work, privacy, versions.
  - Messages for every outcome: saved, not confirmed, failed, not a backup, too new, damaged,
    too large, unreadable, restored, restore failed, undone, undo failed, currency refused.
  - The chosen file is held in memory only. After process death it is picked again, since the
    file permission ends with the activity anyway.
- **Not done here:** the Overview shortcut to backup status (spec §7.1). It is left for Phase 9.
- **Tests:**
  - 28 new (243 total): codec 10, repository 5, Settings VM 6, Settings UI 7. The widget
    refresher test now covers a restore.
  - `BackupRepositoryTest` restores a full data set into a second, empty database and compares
    a fresh export plus month summaries, balances, boosts, closed months, categories and the
    last-added category. The data set covers every kind of record, including a reopened and
    re-closed month, a re-close adjustment, a released boost and an archived category.
  - Other repository tests: a mid-way failure leaves the data as it was; restoring a JPY backup
    over EUR data; an empty install round trip.
  - Lint: 0 errors; the one new warning (a plural) was fixed.
- **Manual check on emulator** (API 37, through the real system picker, saving to Downloads):
  - Back up: the suggested name; "Backup saved"; the file is readable JSON; a second backup
    became "… (1).json".
  - Add an expense → restore → it is gone, and the widget updates → undo → it is back.
  - A hand-edited file → damaged; a non-backup → "isn't a Budzets backup"; a file cut short →
    damaged. Data unchanged each time.
  - Currency picker: JPY disabled ("0 decimals"), USD chosen.
  - Close October + €5 November boost + USD → back up → `pm clear` → restore. Overview,
    Surplus and the widget show the same values as before, in USD.
  - **Not checked:** Google Drive itself. The emulator has no Google account. Check on a real
    phone: back up to Drive, then restore from Drive.

## Decisions from Phase 9 review (approved 2026-10-28)

- **Q18 — Quick add as the task's root:** fix it (see below).
- **Q23 — Android's own backup:** Google cloud backup stays off, but device-to-device transfer
  (setting up a new phone from the old one) carries the database and settings. This revises Q6
  for the transfer case only.
- **Q24 — Release signing:** from a git-ignored `keystore.properties`; without it the release
  build is unsigned.
- **Q25 — Backup status on Overview** (spec §7.1): a reminder card when there is data and no
  backup, or none in 30 days. "Back up" opens Settings.

## Phase 9 — polish and release readiness (done)

- **Q18 fixed:**
  - The widget's "+" now opens `QuickAddActivity`: invisible, `noHistory`, kept out of recents,
    with its own task affinity. It starts `MainActivity` the way the launcher does (MAIN/LAUNCHER,
    `NEW_TASK | RESET_TASK_IF_NEEDED`), then sends `ACTION_ADD_EXPENSE`, which the singleTop
    activity receives in `onNewIntent`.
  - The app's task therefore always starts from a normal launch, so after an update the icon
    opens Overview.
  - Reproduced first (update → icon → Add expense), then checked on the emulator: with the app
    running, "+" opens Add expense on top of the current tab and Back returns there; after an
    update the icon opens Overview; the relay never shows in recents.
  - Found on the way: after **Force stop**, the widget's buttons do nothing until the widget is
    redrawn. Android cancels a force-stopped app's PendingIntents, and opening the app redraws
    the widget. This is platform behaviour, listed in the README.
- **Q25:** `BackupReminder.due` (in `OverviewViewModel`) gives the rule; the banner shows on the
  current month only.
- **Q23:**
  - `data_extraction_rules.xml` (API 31+) excludes every domain from cloud backup and allows
    device transfer except WorkManager's database, whose jobs belong to the old phone's widgets.
  - `backup_rules.xml` (API 28–30) includes the same files with
    `requireFlags="deviceToDeviceTransfer"`, so cloud backups there are empty too.
  - "Undo restore" is now shown only while the safety copy exists (`BackupRepository.lastRestoreAt`):
    the restore marker travels with the settings, the private copy doesn't.
  - Checked with `bmgr` and the local transport: a device-transfer backup restored all data into
    a cleared app, with no Undo banner; a cloud-style backup restored nothing.
  - Side effect on the emulator: the widget instance disappeared during these `bmgr` restores and
    was re-added. Neither `pm clear` nor a density change removes it. The likely cause is
    Android's widget-restore pruning when restoring onto the same device without the launcher's
    data (not chased further).
- **Q24:** `signingConfigs.release` reads `keystore.properties` (storeFile, storePassword,
  keyAlias, keyPassword). `.gitignore` covers it plus `*.jks` and `*.keystore`. The README explains
  how to create a keystore.
- **Release build — found and fixed:**
  - The widget never rendered: it stayed on Glance's loading spinner.
  - Glance pulls in WorkManager 2.7.1, whose consumer rule is `-keep class * extends InputMerger`.
    In R8's full mode that keeps the class but not its constructor, so WorkManager couldn't
    instantiate `OverwritingInputMerger` and Glance's session worker never ran.
  - Fix: `app/src/main/keepRules/workmanager.keep` keeps the constructors.
  - Smoke-tested a signed release on the emulator (throwaway keystore): restore from a file,
    every tab, the expense editor, backup (its file keeps real field names), the widget rendering
    and refreshing, and quick add. No crashes. The release APK is 6.5 MB, the debug one 38 MB.
- **Large fonts and screen sizes:**
  - At font scale 2.0 the five bottom-bar labels broke mid-word. From 1.5 the bar now shows icons
    only, each named for screen readers; below that, labels stay on one line.
  - Every tab was checked at 2.0, and Overview at about 308dp wide; text wraps, nothing is
    clipped.
  - Light and dark themes had been checked in earlier phases and with the widget.
- **Accessibility audit:** every icon button has a description. The icons without one are
  decorative, sitting next to text that says the same thing.
- **Privacy review:**
  - No INTERNET permission. All requested permissions are normal ones from WorkManager:
    WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE.
  - Exported components are the launcher activity, the widget receiver, and library components
    guarded by system permissions.
  - No logging in app code.
- **Clean-ups:** removed the unused `glance-material3` (offered in Phase 7), the template colours
  and a redundant activity label (lint). Lint: 0 errors. All 10 remaining warnings say a newer
  Gradle, AGP or library version exists. Upgrading is left as its own deliberate step, since the
  library versions must stay compatible with built-in Kotlin 2.2.10.
- **Spec kept in step:** `PROJECT_PLAN.md` gained "(Phase 8/9 review: …)" notes where decisions
  refined it (§4 Drive, §6.8, §7.1, §7.6, §14).
- **README.md:** building, release signing, architecture, data/backup/privacy, migrations and
  recovery, known limitations.
  - The recovery recipe copies the database with its `-wal` and `-shm` files. Checked on the
    emulator: the main file alone was 4 KB, with all the data still in the WAL.
- **Tests:**
  - 248 total, 5 new: the quick-add relay, the backup reminder rule and both of its banners, and
    Undo hidden without the safety copy.
  - Fixed two flaky tests, each confirmed before fixing:
    - `SurplusFlowTest`: the new reminder banner moved "Review & close" under the floating
      Add expense button, which took the tap. That step now clicks through the accessibility
      action.
    - `SettingsViewModelTest`: in about half of full-suite runs the state stayed stale. Logging
      showed the stored currency was right while the ViewModel's state never updated. Without a
      subscriber between awaits, `WhileSubscribed` stops on the virtual clock, and the restart
      sometimes never reached the test scheduler. The tests now keep a collector in
      `backgroundScope`.
  - Five full runs in a row were clean. Instrumented tests (the template test) pass on the
    emulator.
- **Not verified here:** Google Drive itself (the emulator has no Google account); see Phase 8.

## Dependency upgrades (2026-10-28)

The upgrades lint flagged, limited to versions released at least two weeks earlier. Release dates
were read from the Maven repositories (the POMs' Last-Modified) and Gradle's release list.

| What | From | To | Released |
|---|---|---|---|
| Gradle wrapper | 9.6.0 | 9.8.1 (with SHA-256) | 2026-10-07 |
| Kotlin (compiler, Compose and serialization plugins) | 2.2.10 | 2.4.21 | 2026-10-08 |
| kotlinx-serialization (core, json) | 1.9.0 | 1.11.0 | 2026-04-09 |
| kotlinx-coroutines | 1.9.0 (app, transitive) / 1.10.2 (tests) | 1.11.0 both | 2026-05-07 |
| Compose BOM | 2026.02.01 | 2026.09.00 (Compose 1.12.1, Material 3 1.4.0) | 2026-09-09 |
| Navigation Compose | 2.9.8 | 2.10.2 | 2026-09-23 |

- **Held back as too new** (lint still lists them; take them from about 2026-11-04):
  - Gradle 9.8.2 (2026-10-19)
  - AGP 9.5.0 (2026-10-28)
  - DataStore 1.3.0 (2026-10-21)
  - kotlinx-serialization 1.12.0 (2026-10-19)
- **Kotlin under AGP 9:**
  - AGP 9.4.1 bundles KGP 2.2.10. The `kotlin` catalog version drives the Compose and
    serialization plugins, and Gradle resolves KGP to the higher version: `buildEnvironment`
    shows `kotlin-gradle-plugin:2.2.10 -> 2.4.21`.
  - KSP 2.3.12 (KSP2, independent of the Kotlin version) still generates Room's code.
  - The compiler gave no new warnings.
- **Coroutines were misaligned:** the app shipped 1.9.0 (from other libraries) while tests ran
  1.10.2. The app now declares `kotlinx-coroutines-android` at the catalog version, so both use
  1.11.0.
- **Not upgraded:** WorkManager stays at Glance's 2.7.1. It isn't declared and lint didn't flag
  it; the keep rule from Phase 9 covers it.
- **Verification:**
  - Each step (Gradle; Kotlin with serialization and coroutines; Compose and Navigation) was
    built and passed the full suite before the next. Then two more full runs: 248 tests, no
    failures.
  - Lint: 0 errors; the 5 warnings are the held-back versions.
  - Instrumented test passes.
  - Signed release smoke test on the emulator: restore from a file, every tab, the expense
    editor (navigation arguments), the boost form, backup, the currency picker, the widget
    rendering and refreshing, and quick add. No crashes. Release APK 6.6 MB.

## Open questions

None currently.
