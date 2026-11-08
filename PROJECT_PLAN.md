# Personal Budgeting App --- Product and Implementation Plan

## 1. Project brief

Build a personal, offline-first Android budgeting app using **Kotlin**,
**Jetpack Compose**, and modern Android architecture. The app is for one
person and must not require an account or a backend. All primary
budgeting data is stored locally on the device. The app can back up and
restore its data through the user's Google Drive.

The core purpose is to make logging spending quick and frictionless,
then show whether spending is on track. A secondary purpose is to
accumulate unspent budget as category-aware surpluses that can be saved
for future goals (for example, a trip) or moved between categories.

This document is the product specification and implementation brief for
Claude Code. Work in small, verifiable stages. Inspect the existing
empty Android project before changing it, preserve its current
package/application configuration where practical, and explain major
decisions.

## 2. Product principles

1.  **Offline-first:** logging expenses, viewing budgets, and managing
    surpluses must work without network access.
2.  **Local ownership:** no account system, no server database, no
    bank-account integration, and no analytics by default.
3.  **Fast entry:** the common path to log a purchase should take only a
    few taps.
4.  **Explainable numbers:** every budget total should be traceable to
    budgets, spending, monthly adjustments, and surplus transfers.
5.  **No silent loss:** backups are user-controlled, restore is
    explicit, and destructive operations require confirmation.
6.  **Category-aware surplus:** unused budget retains its originating
    category until the user deliberately moves it.
7.  **Useful at a glance:** the home screen and Android home-screen
    widget should clearly show the current month's status.
8.  **Testable calculations:** budget arithmetic and surplus accounting
    belong in a domain layer, not in Compose UI code.

## 3. Scope and non-goals

### In scope for the first usable release

-   Create, edit, archive, reorder, and select spending categories.
-   Set an overall budget using daily and/or weekly targets,
    extrapolated to a monthly planning figure.
-   Set per-category daily and/or weekly targets, also extrapolated to
    the month.
-   Set a month-specific budget bump for a category, funded by an
    existing surplus allocation.
-   Log, edit, and delete expenses.
-   View monthly spending by category and overall.
-   Track accumulated surpluses, retaining their category origin.
-   Move all or part of a surplus allocation from one category to
    another.
-   Allocate available surplus to a category for the current month.
-   Display a glanceable Android home-screen widget.
-   Back up and restore app data using Google Drive.
-   Work fully offline except for Drive backup/restore.
-   Unit tests for budgeting, date boundaries, surplus accounting, and
    monthly rollover.

### Explicitly out of scope initially

-   Bank or card account connections, automatic transaction imports, or
    account aggregation.
-   Multiple users, shared budgets, sign-in, or a custom backend.
-   Investment tracking, bills/invoices, receipt OCR, and recurring
    transactions.
-   Currency conversion or multi-currency budgets. Choose one
    user-configured display currency for the initial release.
-   Cloud sync between devices. Google Drive is a backup/restore
    destination, not a live synchronization engine.
-   Ads, tracking, or third-party analytics.

## 4. Important product decisions and proposed defaults

Implement these defaults unless the project owner decides otherwise.
Keep the assumptions easy to change.

-   **Budget month:** calendar month in the device's local time zone.
-   **Currency:** configurable in settings; default EUR (decided in
    Phase 0 review; see `IMPLEMENTATION_NOTES.md`). Store monetary values
    as integer minor units (for example, cents), not floating-point
    numbers.
-   **Expense date:** store a local calendar date, not only a timestamp.
    Also store creation/edit timestamps for audit and sorting. Dates after
    today are refused (decided in Phase 4 review).
-   **Month calculation:** use the actual number of calendar days in the
    month, including leap years.
-   **Daily target extrapolation:** daily target × number of days in the
    month.
-   **Weekly target extrapolation:** weekly target × days in month ÷ 7.
    Use exact rational/integer arithmetic and a documented rounding rule
    when displaying the result.
-   **Daily and weekly targets together:** allow the user to configure
    either or both, but avoid adding both together automatically. If
    both are configured for the same budget, use the **higher of the two
    monthly equivalents** as the planned monthly amount. (Phase 5 review:
    an optional **monthly** target was added; the plan is the highest of
    all configured monthly equivalents.) Show the
    calculation in the budget editor. This prevents accidental
    double-counting of two ways of expressing the same spending limit.
    If the user later wants additive targets, make that an explicit
    alternative setting rather than an implicit rule.
-   **Monthly target:** the extrapolated amount is the month plan, not a
    promise that spending must be evenly distributed by day.
-   **Category totals:** category budgets are a breakdown of the overall
    plan. Warn when the sum of category budgets exceeds the overall
    budget; do not silently change either value.
-   **Expenses:** expenses count against the month and category
    indicated by the expense date.
-   **Month-specific bump:** a bump is an explicit transfer of already
    accumulated surplus into a category's budget for one named month. It
    does not create new money or alter the underlying historical surplus
    ledger.
-   **Surplus semantics:** surplus is the amount of budget left unspent
    when a period is finalized. It remains assigned to the category in
    which it was generated. It is not a bank balance and must be
    labelled accordingly.
-   **Overspend (decided in Phase 0 review):** when a month is closed, a
    category's overspend is recorded as a debit against that category's
    own surplus balance, which may go negative and is repaid by later
    surplus or transfers in. A negative balance cannot fund transfers or
    bumps.
-   **Month finalization:** do not automatically lock a month merely
    because the date changes. Provide an explicit "Close month /
    finalize surplus" action, and explain that it snapshots the unused
    budget into the surplus ledger. Allow correction/reversal through a
    transparent adjustment, rather than silently rewriting history.
    (Phase 6 review: past months and the current month can be closed;
    the overview reminds about unclosed past months that had a budget.)
-   **Drive backup:** use a user-initiated Google Drive file
    picker/authorization flow where possible. The backup file is app
    data, not a sync protocol. Never upload automatically without a
    clear opt-in. (Phase 8 review: the system file picker, with Drive as
    one location; a new dated file each time; restore keeps a private
    copy of the replaced data and offers Undo.)

### Clarification to preserve in implementation

Daily/weekly targets can mean different things: they may be alternative
expressions of a single spending limit, or independent allowances.
Implement the proposed "higher monthly equivalent" rule initially and
cover it with tests. Keep this calculation isolated in one domain
function so it can be changed without rewriting the app.

## 5. Core budgeting model

The implementation must distinguish between:

1.  **Planned budget** --- the month's base plan, derived from the
    overall/category daily and weekly targets.
2.  **Month-specific bump** --- extra category spending capacity funded
    by surplus allocated to that category for that month.
3.  **Actual spending** --- expenses recorded for the period.
4.  **Remaining budget** --- planned budget + applicable bumps −
    spending.
5.  **Accumulated surplus** --- finalized unused budget retained for
    future use, organized by source category.
6.  **Available surplus** --- accumulated surplus not already allocated
    to a month-specific bump or otherwise transferred away.

Never calculate the same surplus twice. A surplus used to fund a
month-specific bump must be reserved/consumed for that month and
unavailable for a second allocation. When a month is reopened or a bump
is removed, restore the funding allocation through a recorded reversal
or transaction, preserving an audit trail.

### 5.1 Suggested formulas

For a budget with optional daily target `D`, optional weekly target `W`,
and month length `N` days:

-   Daily monthly equivalent: `D × N`
-   Weekly monthly equivalent: `W × N ÷ 7`
-   Monthly target `M` (added in Phase 5 review): monthly equivalent `M`
-   Base monthly budget: the available monthly equivalent, or the highest
    of the equivalents if more than one is present.
-   Category monthly base budget: same rule applied to that category's
    targets.
-   Category effective budget for a month: category base monthly
    budget + month-specific bumps allocated to that category.
-   Category remaining: category effective budget − category expenses.
-   Overall effective budget: overall base monthly budget + total
    month-specific bumps across categories, **only if** the product
    decision is that bumps increase the overall cap as well. Recommended
    default: yes, because the bump represents intentional extra spending
    capacity funded by saved surplus. Show base budget and bump
    separately.
-   Overall remaining: overall effective budget − all expenses in the
    month.
-   Available surplus: sum of finalized surplus credits minus transfers
    out, month-bump allocations, reversals, and any other explicit
    adjustments.

Use integer minor units throughout. For divisions such as `W × N ÷ 7`,
calculate at sufficient precision and apply one consistent rounding rule
at the final monetary boundary. Avoid repeatedly rounding intermediate
calculations.

### 5.2 Spending pace and status

The overview should show at least:

-   Total planned budget for the month.
-   Month-specific bumps, displayed separately.
-   Total spent.
-   Remaining budget.
-   Percentage of effective budget spent.
-   Days elapsed and days remaining in the month.
-   A simple status indicator such as "On track", "Watch spending", or
    "Over budget".

If a pace estimate is shown, calculate expected spend by today from the
monthly budget and fraction of calendar days elapsed. Label this as a
pace guide, not a hard daily allowance. Do not present a misleading "on
track" status when the month has no budget configured or the effective
budget is zero. (Q34, 2026-11-08: at the user's request the overview and
widget also show a daily amount next to the pace guide: what was left at
the start of today divided by the days left including today, with what
is left of it today. It is a separate figure; the pace guide is
unchanged.)

## 6. User stories and acceptance criteria

### 6.1 First launch and setup

-   As a user, I can open the app without creating an account.
-   I can choose my currency and set an initial overall budget.
-   I can create a few starter categories or begin with sensible
    editable defaults.
-   The app explains how daily/weekly targets become a monthly amount.
-   If setup is skipped, I can still explore the app and configure a
    budget later.

**Acceptance criteria** - No sign-in is required. - The app can be
opened and used in airplane mode. - Empty states explain the next useful
action. - Default categories are editable and can be archived.

### 6.2 Log spending

-   I can add an expense with amount, category, date, and optional note.
-   The default date is today.
-   The most recently used category may be preselected, provided the
    selection is visible and easy to change. (Phase 4 review: yes, the
    category of the last *added* expense; a category drill-down's own
    category takes precedence.)
-   I can edit or delete an expense later.
-   I can see a list of recent expenses and filter by month/category.

**Acceptance criteria** - The expense form validates amount \> 0 and
requires a category. - The amount input uses an appropriate decimal
keyboard and respects currency precision. - Saving an expense updates
the overview and widget data. - Editing an expense recalculates the
original and new month/category totals. - Deleting an expense requires
confirmation or an immediate undo affordance. - No expense is silently
moved to another month due to UTC/local-time conversion.

### 6.3 Configure overall and category budgets

-   I can set a daily target, weekly target, or both for the overall
    budget.
-   I can configure the same target types for each category.
-   I can see the extrapolated monthly amount before saving.
-   I can change budgets for future months without unintentionally
    changing finalized historical results. (Phase 5 review: a change
    applies from the month selected in the Budgets screen until a later
    scheduled change.)

**Acceptance criteria** - The UI displays the target values and computed
monthly equivalent. - When both targets exist, the UI clearly explains
which rule determines the monthly equivalent. - Category budgets that
sum above the overall budget trigger a warning. - Budget configuration
is persisted locally and survives process death and device restart. -
Historical months use saved month snapshots or versioned budget records,
rather than blindly applying today's settings to the past.

### 6.4 View the monthly overview

-   I can see overall budget, spent, remaining, and progress.
-   I can navigate to previous and next months.
-   I can see category-level spent/budget/remaining values.
-   I can identify categories that are over budget.
-   I can inspect expense details from a category.

**Acceptance criteria** - Totals reconcile exactly with the underlying
expenses and budget records. - Negative remaining amounts are allowed
and clearly represented. - Month navigation uses calendar-month
boundaries in the configured local time zone. - The UI distinguishes
base budget from surplus-funded bumps.

### 6.5 Finalize and accumulate surplus

-   At month end, I can review unused budget and finalize the month.
-   The app calculates unused budget per category and, where
    appropriate, an overall unallocated amount.
-   Finalized surplus records retain the category from which the surplus
    came.
-   I can view accumulated surplus by category and see the transactions
    that changed it.

**Acceptance criteria** - Finalizing a month is explicit and shows a
preview before confirmation. - A month cannot be finalized twice without
a clear "already finalized" state or a deliberate revision flow. - The
implementation must define how category surplus and overall-only surplus
interact. Recommended initial rule: category surpluses are generated
from category budgets; any overall budget amount not assigned to
categories is shown as **unallocated overall surplus**, not silently
assigned to a category. - Over-budget categories do not generate
positive surplus. - A finalized month's snapshot is stable. Corrections
create visible adjustments or a controlled reopen flow. - The user can
inspect the source month and category for each surplus credit.

### 6.6 Move surplus between categories

-   I can move all or part of an available surplus allocation to another
    category.
-   The source category decreases by the transferred amount and the
    destination category increases by the same amount.
-   I can inspect the transfer history.

**Acceptance criteria** - Transfer amount must be greater than zero and
no greater than available surplus in the source category. - The
operation is atomic: both sides are recorded together or neither side
is. - The transfer does not alter historical expenses or historical
budgets. - A transfer has source category, destination category, amount,
date, and optional note. - Moving surplus is different from spending it
and different from allocating it to a month-specific bump.

### 6.7 Allocate surplus as a current-month category bump

-   I can select a category and allocate some available surplus to
    increase its budget for the current month.
-   I can see the source category of the surplus and the destination
    budget category.
-   I can allocate surplus originating in one category to a different
    category, using the same explicit transfer/allocation rules.
-   I can remove or reverse an allocation while preserving history,
    subject to clear constraints.

**Acceptance criteria** - The amount cannot exceed available surplus. -
The allocation is linked to a specific calendar month and destination
category. - The bump is included in that month's effective category
budget and, by default, the overall effective budget. - It does not
increase the base daily/weekly target or future months' budgets. - It
appears as a separate line item in the category and overall budget
details. - Allocated surplus cannot be allocated a second time. - If the
month is finalized, changing a bump requires an explicit, well-defined
revision flow.

### 6.8 Google Drive backup and restore

-   I can create a backup file in Google Drive.
-   I can restore from a backup file I choose.
-   The app explains whether restore replaces local data or merges it.
    Recommended first version: **replace local data after explicit
    confirmation**. (Phase 8 review: replace, plus a private safety
    copy and "Undo restore" until the next restore.)
-   Backup and restore work independently from normal offline app usage.

**Acceptance criteria** - Backup includes all user-created data needed
to reconstruct the app: categories, budgets, expenses, month
snapshots/finalization state, surplus ledger, transfers, allocations,
and settings. - The backup has a schema version and creation
timestamp. - Restore validates file type, version, required fields, and
integrity before replacing local data. - Invalid or unsupported backups
fail safely and leave existing data untouched. - Restore requires a
confirmation that clearly states local data will be replaced. - The app
does not claim backup succeeded until the file operation succeeds. -
Include a way to export or share a local backup file as a fallback if
Drive integration is temporarily unavailable. - Never store Google
access tokens or credentials inside the backup file.

## 7. Information architecture and screens

Keep navigation simple. Use a bottom navigation bar or another clear
Compose navigation pattern.

### 7.1 Overview (home)

Primary screen and default destination.

-   Month selector (current month by default).
-   Overall progress card: spent, effective budget, remaining.
-   Base budget and month-specific bumps shown separately.
-   Pace/status hint.
-   Category list with budget, spent, remaining, and a compact progress
    indicator.
-   A prominent "Add expense" action.
-   Shortcut to surplus management and backup status/settings.
    (Phase 9 review: a reminder card when there is data and no backup
    in the last 30 days, opening Settings.)

### 7.2 Add/edit expense

-   Amount field (prominent and focused on entry).
-   Category picker.
-   Date picker, default today.
-   Optional note.
-   Save and cancel.
-   Validation and useful error messages.

Optimize for speed. Avoid making the note mandatory. Consider a numeric
keypad and a clear one-tap save action.

### 7.3 Transactions

-   Recent expenses list.
-   Filters for month and category.
-   Search notes if straightforward.
-   Edit/delete actions.
-   Empty state for months without expenses.

### 7.4 Budgets

-   Overall budget editor.
-   Category budget list and editors.
-   Daily/weekly target input.
-   Live monthly extrapolation preview.
-   Category budget sum versus overall budget.
-   Historical month selector or summary.

### 7.5 Surplus

-   Available surplus grouped by source category.
-   Separate display for unallocated overall surplus, if any.
-   Ledger/history of credits, transfers, allocations, and reversals.
-   Actions: move surplus, allocate to a month's category bump, inspect
    source.
-   Clearly distinguish **available**, **transferred**, and **allocated
    to a month** amounts.

### 7.6 Settings and backup

-   Currency.
-   Budget calculation explanation and any supported preferences.
-   Backup to Drive.
-   Restore from Drive.
-   Local backup/export fallback. (Phase 8 review: the same picker
    saves to and restores from the phone's own storage.)
-   Last successful backup timestamp and status.
-   App version and data/schema version.
-   Privacy statement: data stays local except for files the user
    explicitly backs up.

## 8. Android home-screen widget

Build a glanceable App Widget using the most appropriate modern Android
widget API supported by the project's minimum SDK and dependencies.
Evaluate **Jetpack Glance** first; if project constraints make it
unsuitable, explain the alternative before proceeding.

### Widget content

At minimum, show:

-   Current month.
-   Spent / effective budget.
-   Remaining amount.
-   A compact progress bar or equivalent visual.
-   Optional top over-budget category or category summary if it fits
    cleanly.
-   A tap target that opens the app's overview.
-   A quick-add-expense action if it can be implemented reliably without
    making the widget cluttered.

### Widget behavior

-   Update after adding, editing, or deleting an expense.
-   Update after budget edits, surplus allocations, month finalization,
    currency changes, or restore.
-   Refresh at month boundaries so the displayed month does not remain
    stale.
-   Avoid frequent background polling; update on relevant app events and
    use an appropriate scheduled refresh only if required by platform
    limits.
-   Read data from the same local source of truth as the app.
-   Handle missing budget and empty database states.
-   Respect Android widget size/resizing constraints and provide a
    useful layout at small sizes.
-   Use accessible text contrast and content descriptions.

Document any platform limitations around widget refresh frequency and
background execution.

## 9. Technical architecture

Use a pragmatic layered architecture with clear separation of concerns.
Do not over-engineer this single-user app.

### Suggested stack

-   Kotlin.
-   Jetpack Compose and Material 3.
-   Navigation Compose, if needed.
-   Room for structured local persistence.
-   Kotlin coroutines and Flow for asynchronous work/reactive UI.
-   ViewModel and lifecycle-aware state collection.
-   DataStore for small preferences such as currency or display
    settings. Do not duplicate core budget records in DataStore.
-   Jetpack Glance for the home-screen widget, if compatible.
-   Android Storage Access Framework / Activity Result APIs or the
    appropriate Google Drive API flow for user-selected backup files.
    Choose and document the specific Drive integration approach before
    implementing it.
-   Kotlin serialization or another robust serialization library for
    versioned backup JSON.
-   Unit tests with JUnit and coroutine test support; Room tests for
    persistence behavior.

Use dependency injection only if it materially improves testability and
clarity. Constructor injection and a small application-level dependency
provider may be enough for the initial app.

### Suggested package layout

Adapt this to the existing project rather than forcing a package rename.

``` text
app/
  data/
    local/
      BudgetDatabase
      dao/
      entity/
      migrations/
    repository/
    backup/
  domain/
    model/
    repository/
    usecase/
    calculation/
  ui/
    navigation/
    overview/
    expenses/
    budgets/
    surplus/
    settings/
    theme/
  widget/
  util/
```

### Architectural rules

-   Compose screens render state and emit user actions; they do not
    contain accounting logic.
-   ViewModels expose immutable UI state using `StateFlow`.
-   Repositories are the boundary between the domain layer and
    persistence.
-   Budget calculations and surplus ledger rules are pure Kotlin
    wherever possible.
-   Database writes that must remain consistent happen in Room
    transactions.
-   Use stable IDs, not display names, to reference categories.
-   Do not make network calls during normal expense logging or overview
    display.
-   Keep currency formatting in a presentation utility; do not store
    formatted strings as monetary values.

## 10. Suggested data model

Refine these entities as implementation reveals needs, but preserve the
domain distinctions. Use Room primary keys and foreign keys where
appropriate. Add indexes for common month/category/date queries.

### Category

-   `id`: stable UUID/string or generated long ID.
-   `name`.
-   `iconKey` or simple icon identifier (optional).
-   `sortOrder`.
-   `isArchived`.
-   `createdAt`, `updatedAt`.

Archiving is preferred over deleting categories that have historical
expenses or surplus records.

### Budget configuration

Store overall and category target configuration in a normalized way, or
use separate entities if that makes the model clearer.

-   `id`.
-   `scope`: overall or category.
-   `categoryId`: null for overall, otherwise a category ID.
-   `dailyTargetMinor`: nullable integer.
-   `weeklyTargetMinor`: nullable integer.
-   `monthlyTargetMinor`: nullable integer (schema v2, Phase 5).
-   `effectiveFromMonth`: calendar month identifier, e.g. `YYYY-MM`.
-   `createdAt`, `updatedAt`.

If supporting future budget changes, version the configuration by
effective month. Do not let editing today's configuration rewrite
finalized past months.

### Expense

-   `id`.
-   `amountMinor`: positive integer in currency minor units.
-   `categoryId`.
-   `expenseDate`: local date stored in an unambiguous representation
    such as ISO `YYYY-MM-DD`.
-   `note`: nullable string.
-   `createdAt`, `updatedAt`.

Define whether expenses in an archived category remain visible;
recommended: yes.

### Month snapshot / finalization

-   `monthId`: `YYYY-MM`.
-   Snapshot of the overall base budget and applicable category budgets.
-   Snapshot of month-specific bumps.
-   Snapshot of spending at finalization, if useful for audit.
-   `finalizedAt`.
-   `revision` or status field if controlled reopen/revision is
    supported.

Use a normalized snapshot table if needed rather than a large serialized
blob. The important requirement is stable historical interpretation.

### Surplus ledger

Prefer an append-only ledger rather than a single mutable balance field.
A ledger makes balances auditable and backup/restore safer.

Possible fields:

-   `id`.
-   `type`: credit, transfer-out, transfer-in, month-allocation,
    allocation-release, adjustment, reversal.
-   `amountMinor`: positive integer; transaction type determines
    direction.
-   `sourceCategoryId`: nullable only for truly unallocated overall
    surplus.
-   `destinationCategoryId`: nullable when not applicable.
-   `sourceMonthId`: optional month in which surplus originated.
-   `targetMonthId`: optional month funded by a bump.
-   `relatedEntryId`: links paired transfers or reversals.
-   `note`.
-   `createdAt`.

For a transfer, record both legs under one database transaction and link
them. Alternatively, use a transfer parent entity with two ledger
entries. Make it impossible for a half-transfer to commit.

The available balance for a category should be derived from ledger
entries, or maintained as a carefully reconciled cache---not updated
independently with no audit trail.

### Month-specific bump / surplus allocation

A separate entity is recommended:

-   `id`.
-   `targetMonthId`.
-   `destinationCategoryId`.
-   `amountMinor`.
-   `sourceSurplusEntryId` or source allocation reference.
-   `status`: active/reversed.
-   `createdAt`, `updatedAt`.

The surplus ledger must record the reservation/consumption of the funds.
The bump record tells the budget engine where and when the extra
spending capacity applies.

### Settings

Store low-volume preferences such as currency code and first day of week
in DataStore or a small settings table. Pick one source of truth per
setting.

## 11. Money, dates, and calculation correctness

These are high-risk areas for subtle bugs; implement and test them
early.

-   Never use `Float` or `Double` for money.
-   Store amounts in integer minor units. Document behavior for
    currencies with zero or three decimal places.
-   Use `java.time` types (`LocalDate`, `YearMonth`) where supported by
    the configured Android SDK/desugaring setup.
-   Use one explicit month-boundary policy across UI, queries, and
    domain logic.
-   Avoid deriving the expense's month from a UTC timestamp.
-   Test month lengths of 28, 29, 30, and 31 days.
-   Define and test rounding for weekly extrapolation.
-   Validate overflow and unrealistic input amounts.
-   Use one shared currency formatter.
-   Keep base budgets, bumps, spending, and surplus ledger balances
    separate in the domain model.

## 12. Backup and restore design

### Backup format

Use a versioned, documented JSON envelope or another portable format.
Include:

-   `schemaVersion`.
-   `appVersion` (informational).
-   `createdAt`.
-   `currencyCode` and relevant settings.
-   Categories.
-   Budget configurations and effective dates.
-   Expenses.
-   Month snapshots/finalization records.
-   Surplus ledger entries.
-   Month-specific bumps/allocations.

Do not include access tokens, credentials, private device identifiers,
or unnecessary diagnostics.

### Backup process

1.  Read a consistent snapshot of local data, preferably from a database
    transaction or a well-defined repository export operation.
2.  Serialize and validate the payload.
3.  Write the file through the selected Drive/document flow.
4.  Verify the write succeeded if the API permits.
5.  Update "last successful backup" only after success.
6.  Show a useful error if cancelled, offline, or unauthorized.

### Restore process

1.  Let the user select a backup file.
2.  Read and parse it without altering current data.
3.  Validate schema version, required fields, IDs, amounts, and
    relationships.
4.  Show backup date and a clear replacement warning.
5.  On confirmation, replace data in one database transaction or via a
    safe staged import.
6.  If any step fails, retain the existing database.
7.  Refresh all screens and the widget after successful restore.

### Drive integration decision

Before coding, inspect the current Android project and choose the
simplest supported route for the desired user experience. Prefer a
user-visible file selection flow and a backup file the user can
find/manage in Drive. If direct Google Drive appData storage is
selected, document its OAuth/authorization setup and recovery behavior.
Do not assume Google Drive authentication is trivial or embed client
secrets in the app. Explain any required Google Cloud Console setup to
the project owner.

## 13. UI and accessibility guidance

-   Use Material 3 components and the existing project theme where
    possible.
-   Support light and dark themes.
-   Make amount, remaining budget, and progress visually easy to scan.
-   Do not rely on color alone to communicate over-budget state.
-   Use meaningful content descriptions for icons and widget controls.
-   Support larger font sizes and reasonable screen widths.
-   Use clear empty states and validation errors.
-   Confirm destructive actions, but avoid confirmation dialogs for
    ordinary expense entry.
-   Keep the app's main "Add expense" action reachable with one hand.
-   Use localized date and currency formatting.
-   Make month-specific bumps visibly distinct from base budgets.

## 14. Privacy and reliability

-   No account registration or backend.
-   No analytics or ad SDKs by default.
-   Do not request unrelated permissions.
-   Request Drive access only as part of the backup/restore action and
    explain why.
-   Do not log expense amounts, notes, or full backup payloads in
    production logs.
-   Ensure backup files are treated as sensitive personal financial
    data.
-   If Android backup/Auto Backup could conflict with the explicit Drive
    backup model, document the behavior and configure it deliberately.
    (Phase 9 review: Google cloud backup carries nothing; device-to-device
    transfer to a new phone carries the database and settings.)
-   Handle database migration failures safely; do not silently wipe user
    data.
-   Include a database export path to support recovery during
    development.

## 15. Testing strategy

### Domain unit tests

At minimum, test:

-   Daily monthly extrapolation for all month lengths.
-   Weekly monthly extrapolation and rounding.
-   Both daily and weekly targets configured.
-   Missing targets and zero targets.
-   Overall and category remaining amounts.
-   Category totals above/below the overall base budget.
-   Month-specific bump added to category and overall effective budgets.
-   Expense edit moving an expense between categories or months.
-   Month finalization creates correct category surplus credits.
-   Over-budget categories create no positive surplus.
-   Surplus transfer all/partial amounts.
-   Transfer cannot exceed available source surplus.
-   Allocated surplus cannot be allocated twice.
-   Allocation release/reversal restores availability correctly.
-   Overall-only unallocated surplus handling.
-   Month finalization/revision behavior.
-   Leap years, year boundaries, and local-date month selection.

### Persistence tests

-   Expense and category CRUD.
-   Foreign key and archive behavior.
-   Ledger transfers are atomic.
-   Finalization cannot accidentally duplicate surplus credits.
-   Migrations preserve existing records.
-   Backup export/import round trip preserves all meaningful data.

### UI tests

-   First launch empty state.
-   Add an expense and see the overview update.
-   Change month and see the correct expenses.
-   Configure category budget.
-   Allocate a surplus bump.
-   Invalid amount validation.
-   Restore confirmation and failure states, where practical.

### Widget checks

-   Empty database.
-   No budget configured.
-   Normal budget with spending.
-   Over-budget month.
-   Month rollover.
-   Refresh after expense add/edit/delete.
-   Refresh after restore.

## 16. Implementation phases for Claude Code

Do not try to build the entire app in one giant change. Finish each
phase, run tests/build, summarize changes, and wait for review before
proceeding when a design decision or ambiguity is encountered.

### Phase 0 --- Inspect and plan

1.  Inspect the entire existing project structure and Gradle
    configuration.
2.  Identify package name, minimum/target SDK, Kotlin version, Compose
    setup, and existing dependencies.
3.  Report what is already configured and what needs to change.
4.  Propose the smallest set of dependencies required.
5.  Identify Google Drive backup approach and any console/OAuth setup
    required.
6.  Create or update a short `IMPLEMENTATION_NOTES.md` documenting
    decisions and unresolved questions.
7.  Do not replace project files wholesale without explaining why.

**Exit criteria:** project baseline is understood, plan is agreed, and
`./gradlew assembleDebug` succeeds before feature work (or any baseline
failure is documented).

### Phase 1 --- Domain model and calculations

1.  Implement money/date/month types and budget calculation functions.
2.  Implement surplus ledger rules and transaction semantics.
3.  Add comprehensive unit tests before UI work.
4.  Define behavior for overall-only surplus and month revisions.
5.  Document any deliberate deviation from this specification.

**Exit criteria:** domain unit tests pass; calculations do not depend on
Android UI classes or Room.

### Phase 2 --- Local persistence

1.  Add Room entities, DAOs, database, and migrations.
2.  Implement repositories and transactional operations.
3.  Add DataStore only for appropriate settings.
4.  Test CRUD, atomic transfers, and finalization idempotency.
5.  Seed starter categories only if the user-facing setup design calls
    for them.

**Exit criteria:** the app can persist and reload categories, budgets,
expenses, and surplus ledger data after process restart.

### Phase 3 --- App shell and overview

1.  Set up navigation and Material 3 screen scaffolding.
2.  Implement overview UI with month selection and category summaries.
3.  Connect it to ViewModel/StateFlow and repositories.
4.  Implement empty, loading, and error states.
5.  Add the "Add expense" entry point.

**Exit criteria:** overview renders real local data and updates
reactively.

### Phase 4 --- Expense logging and transaction history

1.  Build add/edit expense form optimized for quick entry.
2.  Implement recent transactions, month/category filtering, edit, and
    delete.
3.  Verify expense-date semantics and recalculation after edits.
4.  Add UI tests for the most important paths.

**Exit criteria:** logging a purchase is reliable and fast, and history
reconciles with overview totals.

### Phase 5 --- Budget management

1.  Build overall and per-category budget editors.
2.  Show live monthly extrapolation preview.
3.  Add warning for category budgets exceeding the overall plan.
4.  Implement effective-dated budget configuration and historical month
    behavior.
5.  Add tests for editing budgets and viewing previous months.

**Exit criteria:** daily/weekly targets and monthly calculations are
clear, consistent, and tested.

### Phase 6 --- Surplus ledger and month finalization

1.  Implement month-end review and explicit finalization.
2.  Generate category-aware surplus credits exactly once.
3.  Implement surplus overview and history.
4.  Implement full/partial transfers between categories.
5.  Implement month-specific category bumps funded by available surplus.
6.  Implement release/reversal rules and prevent double allocation.
7.  Add confirmation and audit details to important actions.

**Exit criteria:** surplus can be traced from its source month/category
through transfers and allocations; ledger balances reconcile.

### Phase 7 --- Home-screen widget

1.  Evaluate Jetpack Glance against project SDK and requirements.
2.  Build small and expanded widget layouts.
3.  Read from the same source of truth as the app.
4.  Add refresh triggers for relevant mutations and successful restore.
5.  Handle month rollover and missing-data states.
6.  Verify widget behavior on a real or emulated launcher.

**Exit criteria:** widget shows current month, spent, effective budget,
remaining, and a progress indicator, and updates after changes.

### Phase 8 --- Google Drive backup and restore

1.  Implement versioned backup serialization and schema validation.
2.  Implement user-driven Drive file creation/selection using the chosen
    integration.
3.  Implement safe restore with explicit replacement confirmation.
4.  Test invalid, old, unsupported, and corrupted backup files.
5.  Add last-successful-backup status and useful error messages.
6.  Confirm backup/restore triggers a widget refresh.

**Exit criteria:** a complete backup can be restored into a clean
install with all budgets, expenses, categories, surplus history,
allocations, and settings intact.

### Phase 9 --- Polish and release readiness

1.  Add accessibility labels and large-font checks.
2.  Test light/dark theme and common screen sizes.
3.  Review privacy, permissions, logging, and Android backup behavior.
4.  Add database migration/recovery notes.
5.  Run unit tests, instrumented/UI tests where available, lint, and
    release build.
6.  Write a concise README with setup, architecture, backup behavior,
    and known limitations.

**Exit criteria:** build and tests pass; no known data-loss issue
remains; README describes how to run and maintain the app.

## 17. Definition of done

The first release is complete when all of the following are true:

-   [ ] No account or backend is required.
-   [ ] Expenses and budgets persist locally.
-   [ ] Adding an expense is a short, reliable flow.
-   [ ] Overall and per-category daily/weekly targets extrapolate to a
    monthly plan using the documented rule.
-   [ ] Historical months are not silently rewritten by current budget
    edits.
-   [ ] Overview totals reconcile with transactions.
-   [ ] Month finalization generates category-aware surplus without
    duplication.
-   [ ] All/partial surplus transfers between categories work and are
    auditable.
-   [ ] Surplus can fund a month-specific category bump without double
    allocation.
-   [ ] The Android widget displays current budget status and refreshes
    after changes.
-   [ ] Google Drive backup and restore preserve all app data.
-   [ ] Failed restore leaves local data untouched.
-   [ ] Core domain and persistence tests pass.
-   [ ] The app works offline for normal budgeting use.
-   [ ] Setup and maintenance instructions are documented.

## 18. Instructions for Claude Code

Use this document as the source of truth. Start with Phase 0; do not
immediately generate the whole application.

For each phase:

1.  Inspect relevant files before editing.
2.  Briefly state the implementation approach and files likely to
    change.
3.  Make focused, incremental changes.
4.  Run the relevant Gradle build and tests.
5.  Fix failures caused by the changes before moving on.
6.  Summarize what changed, tests run, and any unresolved decisions.
7.  Avoid unrelated refactors and unnecessary dependencies.
8.  Do not claim a build or test passed unless you actually ran it and
    saw it pass.
9.  If a requirement conflicts with the existing project or Android
    platform constraints, explain the trade-off and propose an option
    before making a consequential choice.
10. Keep `IMPLEMENTATION_NOTES.md` and this specification aligned with
    decisions made during development.

**First task:** perform Phase 0 only. Inspect the empty Android project,
report its current configuration, propose the architecture/dependency
plan and Drive backup approach, flag any ambiguities that materially
affect the data model, and wait for approval before implementing
features.
