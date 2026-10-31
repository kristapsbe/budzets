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
  below zero (surplus already moved or reserved). Since Q27 this covers expenses only: budget
  and category changes no longer reach a closed month.
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
  hint (Q3). *Superseded in part by Q27: a closed month can't be edited.*
- **Q12 — Category management:** lives in the Budgets tab: add, rename, archive and
  unarchive, move up and down, and delete only when the category has no history. *Delete is
  superseded by Q28.*
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
    *`updatePeriodMillis` is 0 since "Widget pace and power".*
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

## Performance pass (2026-10-28)

The user found start-up laggy on a **debug build run from Android Studio**: a long wait before
Overview, a spinner before the numbers, and stutter in the first seconds. Much of that comes
from the debug build itself: no R8 and no compiled code. Against the same data, debug took 2.3×
as long as release to show Overview (554 ms against 236 ms, below). The review still found
start-up costs that apply to both builds.

- **Findings:**
  1. No Baseline Profile for the app's own code. The release APK only carried the libraries'
     profiles.
  2. `BudzetsApplication.onCreate` asked AppWidgetManager for widgets (a binder call) on the main
     thread. With a widget, it then built the database and DataStore there, before the first frame.
  3. WorkManager 2.7.1 (brought by Glance, used only by the widget) initialized on every cold
     start through androidx-startup, on the main thread.
  4. The widget refreshed 500 ms after every app start, so a Glance session, WorkManager work and
     database reads competed with the first frames on slow devices.
  5. Overview subscribed to about 15 Room queries. Six were duplicates:
     `observeHasChangesSinceClosing` re-subscribed the month summary (4 queries), the ledger and
     the closed months. StateFlow drops equal states, so this wasn't a second recomposition per
     write. It was doubled queries and recomputation, and for a moment the close status could lag
     the new figures.
  6. All 10 screens showed a spinner the moment they were loading, even when data came a frame or
     two later.
- **Changes:**
  - New `:baselineprofile` module (`com.android.test` + `androidx.baselineprofile`):
    - `BaselineProfileGenerator` covers cold start, starter categories on a fresh install, an
      Overview scroll, every tab, and Add expense.
    - `StartupBenchmark` measures cold start without and with the profile.
    - The generated `baseline-prof.txt` and `startup-prof.txt` (2,113 of their rules are app code)
      are committed under `app/src/release/generated/baselineProfiles/`.
    - The app declares `profileinstaller`.
    - The plugin adds the `benchmarkRelease` and `nonMinifiedRelease` build types. Both are
      signed with the debug key when `keystore.properties` is absent, and the existing keep rules
      cover them.
  - `BudzetsApplication`: the widget check and `widgetRefresher.start()` now run on `appScope`.
    The container's `by lazy` properties are synchronized, so Overview's first access is safe.
  - WorkManager starts on demand:
    - `BudzetsApplication` is a `Configuration.Provider`.
    - The manifest removes `WorkManagerInitializer` from the startup provider. EmojiCompat,
      ProcessLifecycle and ProfileInstaller still run there.
    - `work-runtime` is declared at the 2.7.1 Glance already brought, only to compile against
      `Configuration.Provider`; nothing is upgraded.
  - `WidgetRefresher` no longer refreshes on Room's initial emission (`emitInitialState = false`).
    A one-shot timer refreshes 3 s after start instead
    (`STARTUP_REFRESH_DELAY_MILLIS`, a constructor parameter so tests can shorten it). Writes still
    refresh after the 500 ms debounce. The start-up refresh is kept because it re-arms a midnight
    alarm lost to a force-stop.
  - Overview subscribes once each to the summary, ledger, closed months, categories, configs and
    settings: about 9 queries. Close status and the surplus total come from the same ledger and
    summary, through a new pure `SurplusRepository.hasChangesSinceClosing(ledger, summary)`. It
    replaces `observeHasChangesSinceClosing`, which only Overview used.
  - `ui/components/LoadingIndicator`: the spinner appears only after 400 ms. All 10 screens use it.
  - Overview calls `ReportDrawnWhen { state !is Loading }`, so start-up measurements (and Android's
    "Fully drawn" log) can see when the numbers are on screen.
- **Versions** (all at least two weeks old; the POMs' Last-Modified on maven.google.com):

  | What | Version | Released |
  |---|---|---|
  | Benchmark: `androidx.baselineprofile` plugin, `benchmark-macro-junit4` | 1.5.0 (works with AGP 9.5.0) | 2026-09-09 |
  | UiAutomator | 2.4.0 | 2026-07-01 |
  | ProfileInstaller | 1.4.1 (was 1.4.0, transitive) | 2024-10-02 |
  | WorkManager `work-runtime` | 2.7.1 (unchanged, now declared) | 2021-11-17 |

- **How it was measured:**
  - AVD `Medium_Phone_API_37.0` on an Apple M2, started with `-gpu host`. The AVD's default
    `-no-window` renderer is SwiftShader (software), which made every frame look slow; those
    first numbers were discarded.
  - Seeded data: 6 starter categories with budgets from May, an overall budget, and 713 expenses
    over May to October. The widget was on the home screen.
  - First frame is `am start -W` TotalTime. Content is the "Fully drawn" time from `ReportDrawnWhen`.
    The "before" builds are the previous commit plus only that marker.
  - Before and after were installed alternately, 3 rounds of 10 cold starts each. Runs taken an
    hour apart drifted by ±20 ms, more than the effects measured, so only interleaved runs are
    compared.
  - Release APKs were compiled with their profiles (`speed-profile`), as after background
    compilation or a Play install.

  | Build | First frame, before → after | Overview content, before → after |
  |---|---|---|
  | Debug | 342 → 319 ms | 569 → 554 ms |
  | Release | 209 → 202 ms | 237 → 236 ms |

  Macrobenchmark (`StartupBenchmark` on `benchmarkRelease`, 10 cold starts each). This measures
  the profile against an uncompiled install, which is how a sideloaded APK starts:

  | | No profile | Baseline Profile |
  |---|---|---|
  | Time to initial display | 229.7 ms | 228.3 ms |
  | Time to full display (Overview content) | 502.6 ms | 459.4 ms |
  | Frame CPU time P50 / P90 / P99 | 11.6 / 18.6 / 108.7 ms | 3.4 / 17.8 / 83.6 ms |

- **Reading the numbers:**
  - On this fast emulator the code changes save tens of milliseconds, and the app's profile adds
    little on top of the libraries'. Both matter more on a slow phone, where the widget refresh
    and WorkManager start used to overlap the first second.
  - The clearest effect is the profile cutting per-frame CPU time in the first seconds by about
    3×. That is the early stutter.
  - Spinner: screenshots taken during a debug cold start showed the old build's loading frame
    with a spinner, and the new one with only the top bar and navigation bar until the numbers
    arrived.
  - `gfxinfo` janky-frame percentages for a scroll and tab tour right after launch were the same
    before and after (debug about 5%, release about 2–3%).
- **Found while benchmarking:**
  - With a widget, a kill during the widget's Glance session leaves a WorkManager job.
    JobScheduler then restarts the process (`bound-service SystemJobService`), which broke the
    benchmark's cold-start precondition. `StartupBenchmark` now calls `killProcess()` at the end
    of `setupBlock`.
  - On a phone this is ordinary WorkManager behaviour. It also explains why, in the manual check,
    the widget was already current 2.4 s after a restart that followed a force-stop: the
    interrupted session ran again. The 3 s start-up delay itself is covered by the new
    `WidgetRefresherTest` case.
- **Deviations from the plan:**
  - No test that "one write produces a single Ready state". StateFlow conflation makes that
    unobservable, so the existing Overview tests cover the behaviour.
  - `ReportDrawnWhen` and the benchmark's `killProcess()` were added along the way.
  - README: AGP version corrected to 9.5.0 (commit `10c16f0`).
- Release APK: 6.69 → 6.87 MB. The startup profile makes R8 lay out the dex for start-up.
- **Verification:**
  - 249 unit tests (one new), 3 full runs, no failures.
  - Lint: 0 errors. The 2 warnings are Gradle 9.8.2 and `work-runtime` 2.12.0 being available.
  - Instrumented test passes.
  - Profile generated and both benchmarks pass on the emulator.
  - Manual checks on the R8 release build: quick add from the widget on a cold start opens Add
    expense, and a saved expense showed on the widget within about 1.3 s, so WorkManager's
    on-demand start works under R8. With the debug build, a change made while the app was stopped
    reached the widget after start.

## Performance pass 2: runtime and power (2026-10-29)

The first pass was about start-up. This one asked how the app behaves while it is used and
what it costs in battery, and fixed what was cheap to fix.

- **Already fine (no change):**
  - Nothing polls, and there are no timers or infinite animations. The midnight alarm is `RTC`,
    which doesn't wake the device. The widget's `updatePeriodMillis` is a daily fallback.
    *Corrected later: that period is a wakeup alarm, so it's now 0 (see "Widget pace and power").*
  - Every screen collects with `collectAsStateWithLifecycle` and `WhileSubscribed(5_000)`, so
    hidden tabs and a backgrounded app stop their queries after 5 s.
  - Text fields keep their text in Compose state, so typing never waits for a flow.
  - Lists are lazy and keyed, and formatters are remembered.
  - Month spending is summed in SQL over the `expenseDate` index.
  - Dark theme and dynamic colour are supported.
  - The widget refreshes only after writes (500 ms debounce), once 3 s after start and at
    midnight. Each refresh with no live session runs one Glance session. Glance 1.2's
    `SessionWorker` keeps that WorkManager job alive for 45 s after the first composition, or 5 s
    once the device is idle.
- **Findings:**
  1. R8 optimized only the libraries. `packageScope = setOf("androidx.**", "kotlin.**",
     "kotlinx.**")` came with the project template. The app's own classes (1,419 of 6,114 in the
     release dex) were kept unrenamed and unoptimized:
     - every Compose lambda was a class of its own
     - the Compose compiler's `sourceInformation*` and trace-check calls, which Compose's rules
       remove only from code in scope, still ran on every recomposition
  2. Backup and restore did the JSON work on the main thread. `BackupCodec.encode` (serialize,
     pretty print, a compact copy for the checksum, SHA-256) and `BackupCodec.decode` ran on
     `viewModelScope`'s dispatcher. Only the Room reads were off it.
  3. All screen state was built on the main thread. Room runs queries on its own threads, but
     `map`/`combine` run where the flow is collected, which is `viewModelScope`. That covers
     entity mapping, budget resolution, summaries, ledger sums, close status, the unclosed-months
     scan and the list building. After Save in Add expense, this work for Overview and Spending
     landed on the main thread during the transition back.
  4. Repeated emissions:
     - Every DataStore edit re-emitted every setting. Recording a backup re-emitted the
       currency, and `WidgetRefresher` (which watches `currency.drop(1)`) refreshed the widget
       for nothing, one 45 s Glance job.
     - `hasMonetaryData` re-emitted `true` on every expense, budget or ledger write. Overview
       and Settings then rebuilt their state a second time.
  5. The Spending list (day headers mixed with expense rows) and the Surplus history had no
     `contentType`, so Compose couldn't reuse rows by kind while scrolling.
- **Changes:**
  - `app/build.gradle.kts`: `packageScope` removed, so R8 optimizes everything (the default
    `"**"`). No new keep rules were needed:
    - kotlinx-serialization's rules keep the route serializers that `hasRoute(KClass)` looks up
    - Room's rules keep `BudzetsDatabase_Impl`
    - manifest components are kept by name
    - the committed Baseline Profile is in source names, and R8 rewrites it

    Only 7 app classes keep their names: the Application, the two activities, the two receivers,
    and the database class with its `_Impl`.
  - `BackupRepository.export`, `undoRestore` and `SettingsViewModel.openBackup` encode and
    decode on `Dispatchers.Default`.
  - The six screen `uiState` chains (Overview, Spending, Budgets, Surplus, Month close,
    Settings) end with `.flowOn(Dispatchers.Default)` before `stateIn`, so only the finished
    state reaches the main thread. The editors' small `collect { reference = it }` blocks are
    unchanged; they write Compose state.
  - `SettingsRepository.currency`, `lastBackupAt`, `lastRestoreAt` and
    `ExpenseRepository.hasMonetaryData` drop repeats (`distinctUntilChanged`). New
    `WidgetRefresherTest` case: backup and restore markers don't refresh the widget, and a
    currency change still does.
  - `contentType` on Spending's day headers and expense rows, and on Surplus history rows.
- **Considered, not done:**
  - A composite `(expenseDate, createdAt)` index would avoid a small sort, but it needs a
    migration, and a month is a few hundred rows at most.
  - Skipping widget refreshes whose content didn't change would need the widget state computed
    in the refresher, plus midnight handling.
  - The start-up refresh runs one 45 s Glance session per cold start, also when the process was
    started for the midnight alarm or the daily update; the second update then only recomposes
    the live session. It could be skipped while the midnight alarm is still armed. That is a
    behaviour change, left for the user to decide.
  - `dayName()` builds a formatter per day header. The cost is negligible.
- **How it was measured:**
  - AVD `Medium_Phone_API_37.0` on an Apple M2 with `-gpu host`. `benchmarkRelease` APKs (R8,
    debug-signed, so they install over each other and keep data and the widget) were compiled
    with their profile (profileinstaller broadcast, then `compile -m speed-profile -f`).
  - Seed: 6 categories, budgets from May, 713 expenses over May to October 2026. The backup test
    used 5,000 expenses from May 2023 (a 1.9 MB file). The widget was on the home screen.
  - Builds were installed alternately. Some runs also included "R8 only" and "code changes only"
    builds.
  - Scripts drove the app with `adb shell input`. Per-thread CPU came from `/proc/<pid>/task/*/stat`,
    frames from `dumpsys gfxinfo`, and widget jobs from the `dumpsys jobscheduler` history.
- **Results:**

  | What | Before | After |
  |---|---|---|
  | Release APK | 6.78 MB | 4.84 MB |
  | Dex | 5.65 MB, 6,114 classes (1,419 app) | 3.71 MB, 4,273 classes |
  | Backup of 1.9 MB: slowest frame | 48–200 ms (150 ms typical on the first backup) | 19–65 ms (20 ms typical) |
  | Backup: slow UI-thread frames | 1–3 per backup | 0 |
  | Backup: time until the file is written | 230–380 ms | 230–320 ms |
  | Widget jobs started by one backup | 1 (a 45 s Glance session) | 0 |

  - **Cold start** (`am start -W`, 3 rounds of 10): median first frame 121 → 106 ms, content
    155 → 148 ms. The drift between rounds was larger than that difference, and per round it
    went both ways, so there is no measurable change on this emulator.
  - **Frames and CPU while in use:** no measurable change.
    - Add, save, back: 5.2% vs 5.5% janky frames (6 runs each). Main-thread CPU for 10 saves:
      0.91 s vs 0.97 s, with ±15% between runs.
    - The Spending scroll and the tab tour were within noise.
  - **Where the CPU goes, per saved expense (about 23–25 ms):**
    - about 60% RenderThread (transitions, keyboard, ripples)
    - about 35% main thread
    - about 1% (2 ms) for the app's own data work: Room, all flow computation and the widget

    That small last share is why moving the screen state off the main thread can't show on this
    hardware. No JIT thread ran in either build, so the R8-rewritten Baseline Profile still
    covers the hot code (43,089 profile lines, against 47,454 before R8 merged classes).
  - **Idle:** 0–10 ms of CPU per minute on Overview in the foreground, 20–30 ms per minute in the
    background. No tab draws a frame at rest.
  - **Battery stats for a scripted session** (cold start, two tab tours, a scroll, three
    expenses): app CPU 4.14 s → 3.92 s. One widget job in each session, the start-up refresh.
    Writes during a live session only recompose it.
- **Also found, not changed (left for the user):**
  - Navigation Compose 2.10 crossfades every screen change over 700 ms (`DefaultNavTransitions`),
    where Material's fade-through takes 300 ms.
  - A scratch build with fade-through (90 ms out, 210 ms in) cut 10 add/save round trips from
    933 to 546 frames: −32% RenderThread CPU, −25% CPU in total.
  - Tab switches didn't change: every tap's touch ripple keeps drawing for about 0.9 s
    (53 frames, even on the already-selected tab). New tabs would still appear 0.4 s sooner.
  - It is a visible design change, so it wasn't made here.
- **Emulator notes:**
  - On API 37 a force-stopped app's widget shows Glance's loading layout until the app runs
    again. Both builds do this, so it isn't a regression. An ordinary process death doesn't.
  - While a Glance session runs, `am kill` doesn't end the process. Wait about 50 s after the
    last write to test a real cold start from the widget.
  - With a widget on the home screen, `StartupBenchmark` can still fail with "must not be
    running prior to cold start": the widget's WorkManager job restarts the process between
    `killProcess()` and the launch. Run it without a widget.
- **Macrobenchmark** (`StartupBenchmark`, fresh install, no widget, one pass of 10 cold starts
  each):

  | | Before | After |
  |---|---|---|
  | No profile: initial / full display | 240 / 529 ms | 219 / 514 ms |
  | Baseline Profile: initial / full display | 249 / 482 ms | 263 / 511 ms |

  The two modes moved in opposite directions, within this emulator's noise. Start-up is
  unchanged.
- **Verification:**
  - 250 unit tests (one new), 3 full runs, no failures.
  - Lint: 0 errors. The 2 warnings are the same as before (Gradle 9.8.2, `work-runtime` 2.12.0).
  - Instrumented test passes. `StartupBenchmark` passes on `benchmarkRelease` (R8).
  - Manual smoke test of the R8 build with 5,000 expenses: every passed:
    - every tab
    - category drill-down
    - edit, swipe-delete and undo of an expense
    - overall and category budget editors
    - close, reopen and close again of a month
    - Move surplus; boost and release
    - backup, then restore and undo restore
    - widget renders and updates after a write
    - quick add from the widget on a real cold start (process killed, not force-stopped)

## Skipped-frames check (2026-10-30)

The user saw `Skipped 33 frames!` in logcat on a debug build. No code changed: the message comes
from how a debug build runs its first frame, and a release build stays far below it.

- **The logged launch:** Android Studio's Run on a new DB, right after a build. First frame
  +1.83 s, content +2.92 s, and `Skipped 33 frames` 33 ms after `Displayed`.
- **What the message means here:** Choreographer logs lateness of 30 frames or more when a frame
  starts. Frame 1 is not stuck on anything of its own; it waits for frame 0, whose composition,
  measure and layout all run on the main thread. So the number is roughly frame 0's length. No
  lock contention on the main thread, so `by lazy` isn't blocking it.
- **Measured on seeded data** (6 categories, 713 expenses; AVD on an Apple M2; medians of 5–8 cold
  starts). Lateness is `HandleInputStart - IntendedVsync` from `dumpsys gfxinfo framestats`, which
  works below the log's threshold:

  | Build | Frame 0, main thread | Worst lateness (max) | Content |
  |---|---|---|---|
  | Debug | 156 ms | 9.9 frames (10.4) | 709 ms |
  | Debug, during a Gradle build | 166 ms | 10.8 frames (26.8) | 880 ms |
  | Debug after `cmd package compile -m verify` | 80 ms | 5.5 frames (6.6) | 498 ms |
  | Release, sideloaded, not compiled | 25 ms | 2.8 frames (6.8) | 262 ms |
  | Release, Baseline Profile only | 16.5 ms | 2.4 frames (4.7) | 197 ms |

- **Why debug is slow:**
  - Debuggable apps skip dexopt at install (`status=run-from-apk`; the same `adb install` gives a
    release build `status=verify`), so ART verifies classes at runtime on the main thread. That is
    about half of frame 0.
  - The rest is interpreted code without R8 or the profile.
- **The 33:** not reproduced exactly in about 100 cold starts. Android Studio's sequence (install,
  force-stop, start) on an idle Mac gives about 1 s to first frame. The launch overlapping the
  start of a Gradle build took 1.81 s to first frame and 2.50 s to content, and the worst lateness
  seen in a build was 26.8 frames. Load on the Mac and the post-install busy period fit, but the
  log line itself didn't appear.
- **Release has no hot spot:**
  - First open of each screen, worst main-thread frame, Baseline Profile only: 3.7–13 ms, and
    16–27 ms for whichever screen is the first navigation of the process. Spending's own cost is
    9 ms.
  - The profile journey runs without budgets or expenses, so `SummaryCard` and the budget rows
    aren't in it. Overview's Ready frame was still 12.8 ms, against 11.4 ms with the runtime
    profile merged, which is within noise.
- **Considered, not done:**
  - Moving Room and DataStore creation off the main thread: creating the ViewModel is about 4% of
    frame 0 in debug.
  - The nav graph is about 7% of frame 0 in debug, and it is already remembered.
  - Composing the bottom bar in frame 0 (`currentBackStackEntryAsState()` is null then, so the bar
    arrives in frame 1): this moves work into frame 0 rather than removing it, and would flash the
    bar over Add expense on a quick-add launch.
  - Extending the profile journey: no measurable gain here. Regenerating it uninstalls the app.
- **For measuring performance:** use the `benchmarkRelease` variant. Debug numbers mostly measure
  ART running unverified code.
- **Emulator notes:**
  - `adb install -r` over a foreground app makes the system relaunch it, so a following `am start`
    only reaches the running activity and no `Displayed` is logged. Force-stop first, as Android
    Studio does.
  - `compile -m speed-profile` after some launches merges the runtime profile too. For the
    Baseline Profile alone, run `pm art clear-app-profiles`, then the profileinstaller broadcast,
    then compile, before the first launch.
  - Android Studio's `traceconv` turns a Perfetto trace into text (`traceconv systrace`). Its
    `trace-processor` binary is a server, not a SQL shell.

## Latvian (2026-10-30)

- **Q26 — Choosing the language:** the app follows the device, with no control inside it. Android
  13+ gets the system's per-app setting from `generateLocaleConfig`. An in-app picker for every
  version would have needed AppCompat, plus changes to `MainActivity` and the theme.
- **Resources:** `values-lv/strings.xml` has every translatable string. Format-only strings are
  `translatable="false"`. `res/resources.properties` tells AGP that `values/` is English. The
  generated locale config lists exactly `en` and `lv`.
- **Wording rules** (also at the top of the file):
  - Months arrive as "Aprīlis 2026" and category names as typed, so both are always nominative.
    Sentences that would need another case are reworded so the inserted text stands alone: after
    a colon, in quotes, or as the subject. "Close %1$s" is "Noslēgt: %1$s", and "Delete %1$s?" is
    "Dzēst kategoriju „%1$s”?".
  - Amounts use a form that needs no agreement with the number: "Atlikums: %1$s".
  - Plurals need `zero`, `one` and `other`. `one` also covers 21 and 31, and `zero` covers 0,
    10–20 and 30, so every item keeps its number. October's "× 31 diena" uses `one`.
  - Buttons use the infinitive and messages the formal address (Ievadiet).
- **Terms:**

  | English | Latvian |
  |---|---|
  | Overview | Pārskats |
  | Spending | Izdevumi |
  | Surplus | Pārpalikums |
  | Boost | Papildinājums |
  | Close / reopen a month | Noslēgt / atvērt no jauna |
  | Debit (overspending) | Parāds |
  | Backup / restore | Rezerves kopija / atjaunot |

  "Atlikums" is kept for what is left this month. It is also the word for a bank balance, which
  the app says surplus isn't.
- **Which language a device gets** (measured on the emulator):

  | Device languages | Text | Numbers and dates |
  |---|---|---|
  | `lv` | Latvian | Latvian |
  | `en, lv` | English | English |
  | `de` first, in any order | English | German |

  `[de, lv]` doesn't reach Latvian: library resources ship German, so Android counts German as
  supported and picks it, and the app's own strings fall back to English. The mix of English text
  and German formats is how the app has always looked on a German device. `androidResources.localeFilters`
  would change this, and it was left alone as nothing asked for it.
- **Widget and language changes:** Android does send a widget update when the system language
  changes, but it only worked with the process dead. Measured with the receiver disabled:

  | Process when the language changes | Without `LOCALE_CHANGED` | With it and `languageChanges` |
  |---|---|---|
  | Dead | redraws | redraws |
  | Cached, no session open | stale | redraws |
  | Glance session open | stale, at least 147 s | redraws |

  Cached processes last for hours, and a session is open for about 45 s after any app start, so
  both are ordinary cases. Staleness otherwise lasted until the next data change or midnight;
  opening the app doesn't help, because `WidgetRefresher.start()` does nothing in a live process.
  `WidgetRefreshReceiver` now listens for `LOCALE_CHANGED`, which Android 13+ also sends for a
  per-app change. A live session needs more than `updateAll`: its text is resolved while
  composing, so `BudzetsWidget.languageChanges` keys the content and forces a new composition.
- **Bottom bar:** at 360 dp, "Pārpalikums" was cut off at normal font size, and three labels were
  at font scale 1.3. Labels now shrink to fit with `autoSize`, between 8 sp and the style's size,
  so English keeps its size. The 8 sp floor is scaled by the font setting, and icons-only still
  starts at 1.5. On `benchmarkRelease`, 28 interleaved cold starts each: frame 0 21.1 ms with
  shrinking, 22.1 ms without; content 188 ms and 180 ms. That is within noise.
- **Currency list:** sorted with `Collator` for the locale, because plain character order put
  Č, Š and Ž after Z.
- **Unrelated test fix:** `SurplusViewModelTest`, "releasing a boost…", failed 5 of 12 runs on
  untouched `b915f2f`. `boosts` and `ledger` are separate Room flows under `combine`, so its
  `boosts.isEmpty()` could match before the ledger updated. It now waits for both. Only the test
  was wrong; the screen corrects itself on the next emission.
- **Verified:** 257 tests ×3, lint 0 errors. On the emulator, every screen in Latvian with a real
  system language change, starter categories, comma amounts, backup and restore dialogs, the date
  picker and the widget; the per-app override both ways; `benchmarkRelease` in Latvian. APK about
  18 KB larger. Not checked by a second reader: the translation was written by Claude Code and
  needs a native speaker's read. `Journeys.kt` selects English text, so regenerate the Baseline
  Profile on an English AVD.
- **Emulator notes:**
  - `settings put system system_locales` doesn't survive a reboot. Change the language through
    Settings; the app's own language can be set with `cmd locale set-app-locales`.
  - `am kill` leaves the process alive while a Glance session runs, so check `pidof` before
    calling a process dead.

## Closed months, deleting categories, one-off boosts (2026-10-30)

- **The finding:** closed months weren't frozen. `BudgetRepository.summarize` always re-resolved a
  month from live configs, and `MonthClosure.snapshot` was written but never read for its budgets.
  A budget saved from a closed or earlier month changed that month and marked it "changed since
  closing". Archiving during an early-closed month dropped the category from it, unarchiving put
  it back, and a new category appeared as a row and a filter chip in every past month.
- **Q27 — Closed months:** a closed month reads its budgets from its snapshot, so budget and
  category changes can't reach it. Spending and boosts stay live, so a corrected expense is still
  flagged and closing again still works (Q3). The Budgets tab is read-only for a closed month,
  and showing a monthly amount only, because the snapshot keeps no daily or weekly targets. To
  change one, reopen it.
- **Q28 — Deleting a category:** available on every category in an open month. Without history the
  row goes, as before. With history a hidden row stays, because closed months, old expenses and
  the ledger name it and every reference is RESTRICT. Expenses in open months stay and count,
  marked "(deleted)". Its surplus merges into Unallocated, debts included. Archive stays as the
  undoable option. Delete can't be undone.
- **Q29 — One-off boost:** money from outside the budget, added to a month. It lands in
  Unallocated at once and changes no budget figure until it's moved or used for a boost.
  We chose this over raising the month's overall budget, because that money couldn't have been
  given to a category until the month closed.
- **Q30 — Which months can take one:** any month that isn't closed. It can be removed until that
  month closes, as long as Unallocated still holds the money.
- **Implementation:**
  - `SurplusDao.observeClosureSnapshot` is one joined query rather than the existing mapper over
    two flows (a deviation from the plan), so a close or reopen is never seen half done.
  - A deleted category's pocket merges into Unallocated only when balances are read
    (`SurplusLedger.merged`). Closes, re-closes and reopens still post to its own pocket, so what
    a month recorded never drifts and `MonthFinalizer` needed no change beyond `reopen`'s shortfall
    check. Two alternatives were rejected. A transfer at delete time would leave a closed month's
    credit on an empty pocket, so reopening it would be refused forever. Redirecting closes to
    Unallocated would make every closed month with that category show as changed.
  - Archive and delete start at `ClosingSchedule.firstOpenMonth`, not the current month, so a
    category stays in a month closed early if that month is reopened.
  - One-off boosts are ledger entries (`ONE_OFF_CREDIT`, `ONE_OFF_REVERSAL`), so no new table.
  - `OneOffBoostRoute` is a plain object, not the planned route with a month: nothing passes one.
  - Database v3 adds `categories.deletedAt`. Backup format 2 adds it to categories, along with the
    new ledger types. Version 1 files still restore. Older apps report a v2 file as too new.
- **Behaviour to know:**
  - Reopening puts a month back on live budgets. A budget saved from an earlier open month after
    closing shows up then. In testing, September reopened at the new €1,500 and closed again at
    +€1,010 instead of +€510.
  - A month already "changed since closing" because of a budget edit goes back to Closed with its
    closing figures. Nothing was posted for that drift, so the ledger stays correct.
  - Deleting mid-month drops the category's budget from that open month, as Archive does. With an
    overall budget the amount joins "not assigned to a category"; without one the total falls.
  - A closed month no longer lists a category that had no budget and no spending when it closed,
    because the snapshot doesn't hold one. That is what keeps later categories out of it.
- **Verified:** 325 tests ×3 (68 more than before), lint 0 errors. On the emulator, a v2 database with September
  closed by the old build upgraded in place. September's Overview, month review, Budgets and
  Spending were then identical, line by line, after changing the overall and a category budget
  from August, adding a category, archiving one and deleting one with history. Also: one-off add,
  remove and closed-month refusal, boosting from it, closing October with spending on a deleted
  category (figures checked by hand), reopening and re-closing September, and backup, wipe and
  restore. The new screens in Latvian at 360 dp. `benchmarkRelease`: one-off, delete, backup and
  the widget, no crashes. The new Latvian strings need the same native speaker's read.
- **Emulator notes:** a scrolled list leaves the month row under the app bar, where a tap lands on
  the bar. Scroll to the top before tapping "Next month" in a script. The AVD had no app installed
  at the start, only `lv.kristapsbe.budzets.test`. It was left that way.

## Widget pace and power (2026-10-31)

- **The cost model:** an update with no live session starts a Glance session, which is a WorkManager
  job. It stays open 45 s after drawing (5 s if the device is idle), for about 0.2 s of CPU. That
  timeout is `@RestrictTo(LIBRARY_GROUP)`, so the only way to spend less is to start fewer sessions.
- **Measured** on `benchmarkRelease`, API 37, same AVD and data before and after:

  | | Before | After |
  |---|---|---|
  | Cold app open, nothing changed | 1 job, 45.5 s; 21 s wake lock charged | **no job** |
  | Alarms that can wake the phone | 1 a day (`ELAPSED_WAKEUP`, after any reboot) | **0** |
  | Allowed lateness of the midnight alarm | up to 1 h | 15 min |
  | Save an expense → widget | 3.3 s, 1 job | 3.1–3.3 s, 1 job |
  | Midnight, phone awake | updated 20 s after the alarm's time | within 18 s (same) |
  | Waking from forced Doze | up to about 31 s | up to about 31 s (same) |
  | 12 min idle, asleep then Doze | app absent from the stats | app absent from the stats |

  The save times include going home and polling the screen. They're an upper bound, not the
  update's own speed. An emulator can't give mAh, so these are counts and durations.
- **Changes:**
  - **Start-up refresh only when the alarm is lost.** It existed only to re-arm an alarm lost to a
    force-stop, yet it ran a session on every cold start. `WidgetSchedule.isArmed` uses
    `FLAG_NO_CREATE`: a force-stop clears the PendingIntent, so a missing one means the alarm is gone.
    `cancel` now cancels the PendingIntent too, because it otherwise outlives the alarm.
  - **`updatePeriodMillis="0"`.** The period is a wakeup alarm, the only thing that could wake the
    phone for this app. A force-stopped app misses it anyway, and Android still sends an update when
    a widget is added and after a reboot. `refreshAll` arms the alarm before `updateAll`, so a failed
    update can't lose it.
  - **`TIMEZONE_CHANGED`** re-arms the alarm for the new zone's midnight. `TIME_SET` stays out,
    because network time sync can send it repeatedly and each one would start the process.
  - **`setWindow`, 15 min, instead of `set`.** This is a judgement call, not a measured win. `set`
    allows delivery 75% of the wait late, capped at exactly 1 h on API 37, but the alarm fired on
    time in the one test. It stays because it costs nothing, is still non-waking, and API 28–30
    (which the app supports) couldn't be measured. It didn't change the delay after a wake.
- **Not changed:**
  - Session length: shortening it needs restricted API that can break on any Glance update.
  - Holding updates until the app closes: the 45 s session would start just as you leave, so
    screen-off time would go up.
  - Skipping sessions when the shown state is unchanged: most writes change it, so this only adds
    queries.
- **Behaviour to know:**
  - The delay after a wake belongs to Android, which holds a non-waking alarm. Delivery ranged from
    instant to about 31 s on both builds. Once delivered, the widget updates within 0.3 s.
  - A crash within 500 ms of saving leaves the widget stale until the next change or midnight.
    Before, the next app start would have fixed it.
- **Verified:** 329 tests ×3, lint 0 errors. On the device: force-stop clears the alarm, and opening
  the app re-arms it with one session. A time zone change moved it from 00:01 Riga to 00:01 Tokyo. A
  reboot restored it with no wakeup alarm. Removing the last widget cancels it, and adding a first
  one with the process alive draws, re-arms and still takes saves. No crashes.
- **Emulator notes:**
  - An emulator run from Android Studio closes with the IDE. Don't drive one that's running: it may
    be in use. A throwaway AVD works alongside it (`ANDROID_AVD_HOME` pointing at a scratch folder,
    with a copy of `config.ini`, `-port 5556`, and `-s emulator-5556` on every adb call).
  - That second emulator crashed seconds after the IDE closed, in its gRPC layer, right after
    logging that netsim had gone. Netsim is shared, so it can't be assumed to survive the other one.
  - A leftover `hardware-qemu.ini.lock` from a dead process doesn't stop an AVD from booting.
  - `cmd alarm set-time` and `set-timezone` work from the shell without root, with `auto_time` off.
  - Don't change the date while a session is alive: it keeps its own "today", so nothing redraws.
  - Glance skips a redraw when nothing changed, so `AppWidgetServiceImpl`'s "Trying to notify widget
    update" line can't prove an alarm didn't arrive. Check `dumpsys alarm` for that.

## Month swiping, dragging and swiping categories (2026-10-31)

- **Q31 — Who gets a sideways swipe:** a row takes a swipe left. A swipe right on a row goes to the
  previous month, and everywhere else both directions change month. So a long Spending list can
  go back a month from anywhere, but going forward needs a day header or the month row.
- **Q32 — Month swipe:** a true pager, with the neighbouring month's real figures under the finger,
  on Overview, Spending (with its category drill-down) and Budgets. Not the Boost and One-off forms.
- **Q33 — Dragging categories:** long-press, with no handle. When the hold registers, the row wiggles
  once and keeps a border until it's let go. Budgets tab only, where categories are managed (Q13).
- **Decided along the way:**
  - A category swipe still shows the confirm dialog: deleting can't be undone (Q28), and a stray
    month swipe can land on a row. Expenses stay immediate with Undo.
  - Row swipes are stricter than Material's on both lists: past 40% of the row, or a fling of at
    least 1000 dp/s after 56 dp, where Material takes 56 dp or 125 dp/s. **This changes how expense
    swipes feel.**
  - "Move up" and "Move down" stay in the menu. They're TalkBack's way to reorder.
  - The chip row keeps its own swipes. Left to the pager's defaults, flinging past the end of the
    chips moved seven months in eight flings.
- **Implementation:**
  - `MonthPager` pages are absolute months. The ViewModel's month is the source of truth: arrows
    change it and the pager follows, a swipe moves the pager and reports where it stopped.
  - `MonthStates` keeps one `stateIn` per month, because a page's figures have to survive a rotation
    or an editor for the list to restore its scroll position. `uiState` stays for the top bar, FAB
    and dialogs, and skips Loading so they don't flicker.
  - `beyondViewportPageCount = 0`: only the visible page is live, so at rest this does the same
    database work as before.
  - `SwipeToDelete` replaces `SwipeToDismissBox`, which claims a drag in both directions even with
    one turned off, so a swipe right could never reach the pager.
  - `sh.calvin.reorderable:reorderable:3.1.0` (2026-04-20, Apache-2.0, pure Compose) does the drag.
    It's the first third-party UI library here. It handles auto-scroll at the edges and the
    first-visible-item jump, and the app declares no permissions, so it can't reach the network.
- **Deviations from the plan:**
  - Arrows jump rather than slide. Touching a pager while it slides stops it and swallows the tap,
    and the arrow has moved away: 3 quick taps moved 2 months, 14 moved 2. The pager now waits for
    the month to load and then jumps, so no empty page shows and an arrow is always under the
    finger. 6 taps at 11 a second land, on a debug build. At 16 a second some are lost.
  - The click stayed on `ListItem` rather than moving to a wrapping `Box`. `ListItem` merges its own
    semantics, so TalkBack would have had a button with no name beside a name it couldn't press. The
    wiggle's layer therefore also turns the touched area, which is harmless: pointer deltas are
    measured in one transform, and nothing checks bounds once the drag has started.
  - `settledPage` isn't what it sounds like: it changes when a drag hands over to its fling, before
    the pager stops. Reporting from it lost every swipe, so `MonthPager` watches for idle itself.
  - A month that's been left starts at the top of its list, so its name shows. `rememberLazyListState`
    inside a pager would otherwise bring it back scrolled, with no month in sight.
- **Found while verifying — the profile was mostly dead:** `release` enables R8 through the
  `optimization` block, and the Baseline Profile plugin's `isMinifyEnabled = false` doesn't reach
  that block, so `nonMinifiedRelease` was minified. **8,444 of the committed profile's 12,229 rules
  named obfuscated classes that match nothing in a release.** `build.gradle.kts` now turns it off in
  `finalizeDsl`. The regenerated profile has 23,883 rules, all with real names. Check the rule count
  and for names like `ao0` after regenerating.
- **Measured** on `benchmarkRelease`, AVD `Medium_Phone_API_37.0` on an M2 with `-gpu host`, 16
  categories and 77 expenses, each build compiled with its own profile:

  | Interleaved cold starts, 36 each | Before | After |
  |---|---|---|
  | Initial display, median / P90 | 140.0 / 169 ms | 131.5 / 147 ms |
  | Full display, median / P90 | 187.5 / 226 ms | 178.5 / 199 ms |
  | APK | 4.93 MB | 5.18 MB |

  These combine the new code and the repaired profile, so they don't price the pager alone.

  | `StartupBenchmark` on the new build, 10 each | No profile | Baseline Profile |
  |---|---|---|
  | Initial display | 226.7 ms | 190.5 ms |
  | Full display | 494.8 ms | 380.7 ms |
  | Frame CPU P50 / P90 / P99 | 2.7 / 17.3 / 78.8 ms | 3.2 / 17.6 / 90.1 ms |

  12 month swipes: Overview 3.19% janky of 439 frames, P99 20 ms; Spending 3.06% of 425, P99 21 ms.
  A never-visited month after a cold start had its figures in the first screenshot of a drag.
- **Verified:** 367 tests ×3 (38 more than before), lint 0 errors, `StartupBenchmark` passes. On the
  device: both swipe directions on all three tabs and in the drill-down, including from a scrolled
  list; chips; arrows and far jumps; delete, Undo and Cancel; a hold with and without moving
  (measured: no border at 250 ms, ±0.95° tilt, settling to 0°); auto-scroll at both edges; the order
  after a force-stop, on Overview and in the chips; closed, archived and deleted rows; rotation,
  returning from an editor, and "Don't keep activities"; Latvian at 360 dp. The three gestures again
  on R8. No crashes.
- **Not verified:** TalkBack can't be exercised on the AVD. The pager reports a page count of 120,000
  to accessibility services, which may be announced oddly. A real phone's feel, especially of the
  wiggle at full speed: it was only measured here, not watched.
- **Emulator notes:**
  - `-read-only` discards everything on exit, so the app and data on the AVD stay as they were.
  - A chain of `adb shell input tap` with no sleep fires about 16 taps a second, faster than anyone
    can. Don't read lost taps from that as a bug without measuring by spacing.
  - zsh doesn't split unquoted variables, and `timeout` isn't installed.
  - `Medium_Phone_API_37.0`'s long-press timeout is 400 ms.

## Screen transitions (2026-10-31)

- **Asked for:** going back from a category opened on the overview shrank the screen towards its
  centre. It should fade the way opening does, and the fade should be slightly faster.
- **Cause:** the `NavHost` set no transitions. navigation-compose 2.10.2 fades on open and on the
  back arrow (`tween(700)`), but a back swipe is a separate path, whose default is
  `scaleOut(targetScale = 0.7f)` for the leaving screen and a spring `fadeIn` for the one below.
- **Decided:**
  - Set on the `NavHost`, so it covers every screen and tab switch, not just the category route.
    A category-only fix would have to override both that route and the overview's return
    transition, and would leave the shrink everywhere else.
  - One `navFade` spec, 500 ms, for open, close and swipe. **Opening is faster too**, so the two
    stay identical. `popEnterTransition` and `popExitTransition` default to the enter and exit
    values, so only the predictive pair needs setting beside them.
  - During a swipe the fade follows the finger, and the 500 ms applies once it lifts.
- **Verified:** 367 tests, lint 0 errors. On `Medium_Phone_API_37.0` with gesture navigation and a
  debug build:
  - Held swipes on the category screen and the expense editor, screenshotted with the finger down:
    a full-size crossfade with no shrink. Finishing lands on the screen below.
  - A cancelled swipe returns the category screen, and its chips still work. A quick flick works.
  - Time to the last changing frame, from bursts of screenshots about 200 ms apart:

    | Path | Still changing at | Settled by |
    |---|---|---|
    | Open a category | 353 ms | 558 ms |
    | Back arrow | 488 ms | 700 ms |
    | Tab switch | 651 ms | 852 ms |

    The tab switch runs past 500 ms. The navigation bar's indicator animates in the same frames,
    and the two weren't separated.
- **Not verified:** a release build, three-button navigation, the old build's timings (700 ms is
  from the library source), and how it feels on a real phone.
- **Emulator notes:**
  - To test predictive back, hold the gesture with `input motionevent DOWN`, `MOVE` and `UP`, and
    `screencap` before the `UP`. `input swipe` can't be held.
  - A `screencap` straight after `input tap` shows the frame before the change. Allow 200 ms.
  - The expense editor opens with the keyboard up, and that takes the first back gesture.

## Holding a row mid-swipe (2026-10-31)

- **Reported:** dragging a category left and stopping with the finger down made the whole page lurch
  toward the next month and spring back.
- **Cause:** `SwipeToDelete` claimed the swipe but didn't keep hold of it.
  - In foundation 1.12.1 a drag node that loses a gesture doesn't drop out. The pager and the
    `LazyColumn` wait in `AwaitGesturePickup` until every finger lifts. There's no flag for this.
  - One unconsumed change sends them back to slop detection, measured from the original touch-down,
    so they're already past the slop and the next unconsumed change starts a drag.
  - `horizontalDrag` leaves any change with zero x unconsumed. A moving finger rarely reports one. A
    resting finger reports little else.
  - The pager's first delta is the row's whole travel minus the slop. The row's next sideways change
    cancels that drag, and the pager flings at zero velocity back to its page.
- **Fixed:** `followToLift` replaces `horizontalDrag` and consumes every change for every finger until
  the last one lifts. `drag` wouldn't do: `HitPathTracker` compares local positions, so a change with
  no movement at all still arrives after the row has shifted under the finger. **Don't put
  `horizontalDrag` back.** Expense rows share the component, so they get this too.
- **Fixed in the same gesture:** a fast swipe, a pause of any length and a lift counted as a fling,
  because the tracker never saw the lift and speed is measured at the last sample. A category opened
  the delete dialog and an expense was deleted. The tracker now gets the lift, so its 40 ms reset
  applies. A flick lifted at once still flings.
- **Measured, same scripted gesture on both builds** (row dragged 300 px, then y-only moves, 21 px
  slop at 420 dpi):

  | Finger held down | Before | After |
  |---|---|---|
  | "Next month" arrow, x (944 at rest) | 665, moved 279 px | 944 |
  | November's page | on screen | not on screen |
  | After a few px more sideways | back at 944 | 944 |

  The Robolectric test failed the same way: 84 px for a 100 px swipe, with Robolectric's slop of 16.
- **Verified:** 370 tests ×3 (3 more), lint 0 errors. Two of the new tests failed before the fix. On
  `Medium_Phone_API_37.0`, debug build, no crashes: delete past 40% with the dialog and Cancel; short of
  40%; swipe right to the previous month; swipe left off a row to the next month; a long-press reorder
  with a rest mid-drag; an expense row's held swipe, delete and Undo; a closed month's row both ways.
- **Not verified:** a real phone, where a resting finger's noise is real and not scripted. A release
  build. Handing over to a second finger, because `input motionevent` has one pointer. The `LazyColumn`
  taking over was read from the source and not reproduced: with the fix, 200 px of vertical drift
  doesn't scroll the list, but the old build wasn't tried.
- **Emulator notes:**
  - A closed month with no budgets at closing shows no category rows. Budget it first to test a row.
  - On Spending the summary line also shows the amount, and a swipe there is a plain month swipe, so
    find the row by its category name.
  - A row's text disappears from a `uiautomator` dump once it's swiped off screen. Read the page's
    position from the month arrows.

## Open questions

None currently.
