// FlameRealms — full-codebase bug review (M0+M1+M2), find -> verify -> fix -> build.
//
// Scope: everything currently implemented (bootstrap, realm core, economy foundation).
// Each dimension agent gets the exact file list to read for its area — no agent is asked
// to read PROJECT.md/TECHNICAL_SPEC.md (thousands of lines); the invariants that matter
// (never block the main thread, balance+ledger atomicity, fixed lock ordering, write-through
// cache only after commit, money is never double/float except the one Vault-bridge boundary)
// are restated directly in each prompt.

export const meta = {
  name: 'flamerealms-bug-review',
  description: 'Review the existing FlameRealms codebase (M0-M2) for real bugs across 5 dimensions, verify each finding, fix confirmed ones, then verify the build',
  phases: [
    { title: 'Find', detail: '5 dimension agents read the relevant files and report findings' },
    { title: 'Verify', detail: 'each finding re-checked against the actual code before it counts' },
    { title: 'Fix', detail: 'apply fixes for every confirmed finding' },
    { title: 'Build', detail: './gradlew build + unit tests, fix any regression' },
  ],
}

const PACKAGE = 'com.flamerealms'

const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string' },
          location: { type: 'string', description: 'method/line or section name' },
          summary: { type: 'string' },
          detail: { type: 'string', description: 'exact failure scenario: what input/timing causes what wrong behavior' },
          severity: { type: 'string', enum: ['low', 'medium', 'high'] },
        },
        required: ['file', 'summary', 'detail', 'severity'],
      },
    },
  },
  required: ['findings'],
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    refuted: { type: 'boolean', description: 'true if this is NOT a real bug (false positive, intentional, or already handled elsewhere)' },
    reasoning: { type: 'string' },
  },
  required: ['refuted', 'reasoning'],
}

const DIMENSIONS = [
  {
    key: 'async-threading',
    prompt: `
Audit the FlameRealms Paper plugin (package ${PACKAGE}) for main-thread-blocking and
thread-hopping bugs. Read these files in full:
  src/main/java/com/flamerealms/persistence/AsyncDatabaseExecutor.java
  src/main/java/com/flamerealms/persistence/DatabaseManager.java
  src/main/java/com/flamerealms/FlameRealmsPlugin.java
  src/main/java/com/flamerealms/command/realm/RealmCommand.java
  src/main/java/com/flamerealms/service/RealmServiceImpl.java
  src/main/java/com/flamerealms/service/EconomyServiceImpl.java
  src/main/java/com/flamerealms/service/TreasuryServiceImpl.java
  src/main/java/com/flamerealms/economy/VaultEconomyBridge.java

Project rule (unchanged since M0): no DAO/JDBC call may ever execute on the Paper main
thread; every DB-touching call must dispatch through AsyncDatabaseExecutor.submit(...), and
every CompletableFuture continuation that touches the Bukkit API (sendMessage, an online-
player lookup, scheduling, etc.) must hop back with Bukkit.getScheduler().runTask(plugin, ...)
before doing so — never .thenAccept(...) straight into a Bukkit call from a worker thread,
never .join()/.get() on the calling thread.

Look specifically for:
- Any code path that reaches JDBC/Connection directly without going through
  AsyncDatabaseExecutor.submit(...).
- Any future continuation (.thenAccept/.thenApply/.thenCombine/.exceptionally/.whenComplete)
  that touches Bukkit API (player.sendMessage, Bukkit.getPlayer, Bukkit.getOfflinePlayer,
  scheduler calls) WITHOUT first hopping back via Bukkit.getScheduler().runTask(...).
  Bukkit.getOfflinePlayer is itself a blocking call if it has to hit disk/network for an
  uncached player — check whether it is ever invoked in a context where that would matter.
  Bukkit.getOfflinePlayer as used for reading realm.leaderUuid()'s name.
- VaultEconomyBridge's deliberate .join()/.get() blocking-on-future — confirm it is used ONLY
  there and does not leak into any other calling context, and that it doesn't create a
  deadlock (a Vault call arriving ON the async executor's own thread pool would deadlock if
  the executor is bounded and all threads are blocked waiting on each other — check whether
  this is actually prevented or just assumed).
- AsyncDatabaseExecutor's shutdown/drain-with-timeout logic in FlameRealmsPlugin.onDisable():
  does it actually wait for in-flight work, or could the JVM exit mid-transaction? Is the
  pool bounded correctly (a thread-pool size from config, not unbounded)?
- Race between plugin startup (realmCache.loadAll async) and the command tree being
  registered — could a player run /realm info before the cache warm-up future completes,
  and if so what actually happens (crash vs. graceful "not in realm" false negative)?

Do NOT edit any files — this is a read-only investigation. Report findings via the required
schema; if you find nothing, return an empty findings array. Do not report purely stylistic
issues — only things that are actually wrong (wrong behavior under some real input/timing/
concurrency scenario), and state that scenario concretely in "detail".
`,
  },
  {
    key: 'money-ledger',
    prompt: `
Audit the FlameRealms Paper plugin (package ${PACKAGE}) for money/ledger correctness bugs.
Read these files in full:
  src/main/java/com/flamerealms/domain/Money.java
  src/main/java/com/flamerealms/persistence/dao/LedgerDao.java
  src/main/java/com/flamerealms/persistence/jdbc/JdbcLedgerDao.java
  src/main/java/com/flamerealms/persistence/jdbc/JdbcPlayerWalletDao.java
  src/main/java/com/flamerealms/persistence/jdbc/JdbcRealmDao.java
  src/main/java/com/flamerealms/service/EconomyServiceImpl.java
  src/main/java/com/flamerealms/service/TreasuryServiceImpl.java
  src/main/java/com/flamerealms/economy/VaultEconomyBridge.java
  src/main/resources/db/migration/V2__economy.sql

Project invariants: money is ALWAYS a long integer number of cents (never double/float)
except inside VaultEconomyBridge, which is the one sanctioned boundary. A balance mutation
and its transactions-table ledger row must always commit in the SAME database transaction —
"a balance change with no corresponding transactions row is a bug." "Can't go negative" is
enforced by a single CONDITIONAL UPDATE (... WHERE balance_cents + ? >= 0), never
read-then-write. TreasuryService's fixed lock ordering is player-wallet-then-realm-treasury
in EVERY method (including withdraw, where money conceptually flows realm->player but the
code still touches the player row first — this is intentional, don't flag it by itself).

Look specifically for:
- Any place a balance column is updated without an accompanying ledger insert in the same
  transaction (or vice versa).
- Off-by-sign or off-by-direction errors in the delta passed to recordAndApplyToPlayer/
  recordAndApplyToRealm (e.g. a deposit crediting the wrong side, a withdraw's amount sign
  flipped).
- Money's arithmetic: add/subtract/comparison correctness, overflow handling, whether
  negative Money instances can be constructed where they shouldn't, whether toString's
  cents-to-dollar formatting is correct for negative amounts and for cents < 10 (e.g. does
  105 cents format as "$1.05" and not "$1.5"?).
- The conditional-update SQL in JdbcPlayerWalletDao/JdbcRealmDao: does tryAdjustBalance
  actually reject going negative for every sign of deltaCents, and does it correctly report
  "no row affected" as false (not throw, not silently succeed)?
- VaultEconomyBridge: correctness of the double<->cents conversion (rounding direction,
  precision loss for large balances), and whether any Vault Economy method it implements
  returns a wrong/misleading value (e.g. depositPlayer returning success info) given
  EconomyService's actual semantics.
- BigDecimal parsing of user-entered amounts in RealmCommand (deposit/withdraw): re-verify
  the scale/rounding logic is actually exact cents with no silent rounding.

Do NOT edit any files — read-only investigation. Report via the required schema; empty array
if nothing found. Only real defects, not style. State the concrete failure scenario in
"detail".
`,
  },
  {
    key: 'persistence-schema',
    prompt: `
Audit the FlameRealms Paper plugin (package ${PACKAGE}) for persistence/schema/resource-leak
bugs. Read these files in full:
  src/main/resources/db/migration/V1__realm_core.sql
  src/main/resources/db/migration/V2__economy.sql
  src/main/java/com/flamerealms/persistence/jdbc/JdbcRealmDao.java
  src/main/java/com/flamerealms/persistence/jdbc/JdbcRealmMemberDao.java
  src/main/java/com/flamerealms/persistence/jdbc/JdbcRealmRankDao.java
  src/main/java/com/flamerealms/persistence/jdbc/JdbcPlayerWalletDao.java
  src/main/java/com/flamerealms/persistence/jdbc/JdbcLedgerDao.java
  src/main/java/com/flamerealms/persistence/DatabaseManager.java
  src/main/java/com/flamerealms/cache/RealmCache.java
  src/main/java/com/flamerealms/util/UuidCodec.java

Look specifically for:
- Resource leaks: any PreparedStatement/ResultSet/Connection not opened in try-with-resources
  (or otherwise guaranteed to close on every exit path, including exceptions).
- SQL correctness: parameter binding order matching placeholder order, correct types (BINARY
  UUID handling via UuidCodec used consistently both directions), generated-key retrieval
  correctness, any query missing a WHERE clause it needs.
- RealmCache: is it EVER mutated before the corresponding transaction has committed (the
  class's own contract requires write-through-after-commit only, never speculative)? Does
  loadAll's warm-up query correctly exclude disbanded realms and orphaned members? Is there
  any place a realm/member mutation forgets to update the cache at all, leaving it stale?
- UuidCodec: round-trip correctness (toBytes/fromBytes), behavior on a null or wrong-length
  byte array.
- Foreign key / constraint correctness in the migrations: do the FOREIGN KEY clauses actually
  use the "FOREIGN KEY (col) REFERENCES table(col)" form (required for MariaDB/MySQL to
  actually enforce it) rather than a bare inline "REFERENCES" column-level shorthand (which
  MySQL/MariaDB silently ignores as a real constraint)? Does uq_player_one_realm actually
  enforce "one realm per player" the way the code assumes?
- Connection autocommit handling: does every method that opens a transaction reliably restore
  the connection's original autocommit state afterward (including on the exception path), so
  a later reuse of a pooled connection doesn't inherit a wrong autocommit setting?

Do NOT edit any files — read-only investigation. Report via the required schema; empty array
if nothing found. Only real defects, not style. State the concrete failure scenario in
"detail".
`,
  },
  {
    key: 'command-layer',
    prompt: `
Audit the FlameRealms Paper plugin (package ${PACKAGE}) command layer and config loading for
bugs. Read these files in full:
  src/main/java/com/flamerealms/command/realm/RealmCommand.java
  src/main/java/com/flamerealms/config/Messages.java
  src/main/java/com/flamerealms/config/DatabaseConfig.java
  src/main/resources/messages.yml
  src/main/resources/plugin.yml
  src/main/resources/config.yml

Look specifically for:
- Every exception type RealmService/EconomyService/TreasuryService can actually throw or
  fail a future with — cross-check describeError()'s switch against the full set of
  ...ServiceException subclasses in src/main/java/com/flamerealms/service/exception/ — is
  any of them silently falling through to the generic "error-unexpected" message when a more
  specific one exists and should apply?
  MissingPermissionException should apply?
- Null/edge-case handling: Bukkit.getOfflinePlayer(realm.leaderUuid()).getName() returning
  null for a player who has never been seen by this server — is the fallback correct and
  does it read cleanly?
- Brigadier tree correctness: any literal/argument name collision, any subcommand missing
  its permission .requires(...) gate, any subcommand whose messages.yml key referenced by
  Placeholder doesn't actually exist in messages.yml (cross-check every messages.get("...")
  call in RealmCommand.java against messages.yml's actual keys).
- Messages.load(): does it correctly copy messages.yml to the plugins/FlameRealms data folder
  on first run only, and never overwrite an existing customized copy on a later restart, as
  documented?
- DatabaseConfig.fromConfig(): correct defaults/type coercion from config.yml, does a missing
  or malformed value crash startup with a clear error or fail silently with a wrong default?
- parseAmountToCents: any input that should be rejected but isn't, or vice versa (e.g.
  leading/trailing whitespace, "+5", scientific notation via BigDecimal's constructor,
  extremely long digit strings).

Do NOT edit any files — read-only investigation. Report via the required schema; empty array
if nothing found. Only real defects, not style — a documentation/command-naming inconsistency
with TODO.md is NOT in scope for this pass. State the concrete failure scenario in "detail".
`,
  },
  {
    key: 'tests-correctness',
    prompt: `
Audit the FlameRealms Paper plugin (package ${PACKAGE}) test suite for tests that don't
actually verify what they claim, and for fakes that could mask a real production bug. Read
these files in full:
  src/test/java/com/flamerealms/domain/MoneyTest.java
  src/test/java/com/flamerealms/service/EconomyServiceImplTest.java
  src/test/java/com/flamerealms/service/TreasuryServiceImplTest.java
  src/test/java/com/flamerealms/service/RealmServiceImplTest.java
  src/test/java/com/flamerealms/service/fake/FakeLedgerDao.java
  src/test/java/com/flamerealms/service/fake/FakePlayerWalletDao.java
  src/test/java/com/flamerealms/service/fake/FakeRealmDao.java
  src/test/java/com/flamerealms/service/fake/FakeRealmMemberDao.java
  src/test/java/com/flamerealms/service/fake/FakeRealmRankDao.java
  src/test/java/com/flamerealms/service/support/InlineAsyncDatabaseExecutors.java
  src/test/java/com/flamerealms/persistence/jdbc/EconomyLedgerIT.java
  src/test/java/com/flamerealms/persistence/jdbc/JdbcRealmDaoIT.java

Look specifically for:
- Fakes (FakeLedgerDao especially) that DON'T reproduce the real atomicity/negative-balance
  guarantee JdbcLedgerDao provides — if the fake always "succeeds" or doesn't apply the same
  conditional-update logic, a test using it could pass even if the real JDBC implementation
  has a bug, because the fake never exercises the failure path being tested.
  guarantee JdbcLedgerDao provides.
- Assertions that don't actually assert the thing the test name/comment claims (e.g. a test
  named "...rejectsInsufficientFunds" that never actually asserts the balance was left
  unchanged, or a concurrency test that doesn't actually assert on the final state).
- InlineAsyncDatabaseExecutors: does it genuinely run submitted work as production code would
  (same connection-per-submission contract), or could it hide a bug that only manifests with
  a real thread pool (e.g. a method that accidentally relies on running on the calling thread)?
- Any test whose "before" state is not what the test comment claims, or that would pass even
  if the production code under test were deleted/no-op'd (a tautological or vacuous
  assertion).
- Testcontainers-based tests (EconomyLedgerIT, JdbcRealmDaoIT): do they run both V1 and V2
  migrations before asserting, and do their concurrency assertions actually pin down the
  right invariant (final balance non-negative AND the right count of successes) rather than
  just "it didn't throw"?

Do NOT edit any files — read-only investigation. Report via the required schema; empty array
if nothing found. Only real defects, not style. State the concrete failure scenario in
"detail".
`,
  },
]

const results = await pipeline(
  DIMENSIONS,
  d => agent(d.prompt, { label: `find:${d.key}`, phase: 'Find', schema: FINDINGS_SCHEMA }),
  (review, d) => parallel((review?.findings ?? []).map(f => () =>
    agent(`
A code-review pass over the FlameRealms plugin reported this finding. Re-verify it yourself
against the ACTUAL current file content (read the file fresh, don't trust the description
below blindly) before deciding.

File: ${f.file}
Location: ${f.location ?? '(unspecified)'}
Summary: ${f.summary}
Detail (claimed failure scenario): ${f.detail}
Claimed severity: ${f.severity}

Read the exact file and surrounding context. Decide: is this a REAL bug (something that
actually produces wrong behavior under some reachable input/timing/concurrency, given how
this codebase's other pieces actually call into it) — or is it a false positive (the
described scenario can't actually happen, is already guarded elsewhere, or is an intentional
documented simplification)? Default to refuted=true if you are not confident it is real.
`, { label: `verify:${d.key}:${(f.file || 'finding').split('/').pop()}`, phase: 'Verify', schema: VERDICT_SCHEMA })
      .then(v => ({ ...f, dimension: d.key, verdict: v }))
  ))
)

const confirmed = results.flat().filter(Boolean).filter(r => r.verdict && !r.verdict.refuted)

log(confirmed.length > 0
  ? `${confirmed.length} confirmed bug(s) found across ${DIMENSIONS.length} dimensions — fixing.`
  : `No confirmed bugs found across ${DIMENSIONS.length} dimensions.`)

// ---------------------------------------------------------------------------
// Fix confirmed findings
// ---------------------------------------------------------------------------
phase('Fix')
let fixReport = null
if (confirmed.length > 0) {
  const findingsList = confirmed
    .map((f, i) => `${i + 1}. [${f.severity}] ${f.file} — ${f.summary}\n   Detail: ${f.detail}\n   Why it's real: ${f.verdict.reasoning}`)
    .join('\n\n')

  fixReport = await agent(`
Continuing work on the FlameRealms Paper plugin (package ${PACKAGE}) in the current
directory. A code review + independent verification pass confirmed the following real bugs.
Fix EACH ONE directly (Read/Edit/Bash access to the whole project) with the minimal correct
change — do not refactor unrelated code, do not add speculative abstractions, match this
codebase's existing style exactly (it favors explicit inTransaction()-style helpers, explicit
Javadoc explaining WHY a non-obvious choice was made, no third-party libraries beyond what's
already a dependency).

${findingsList}

For each one: make the fix, then briefly note what you changed. If, while fixing, you
determine one of these was actually NOT a bug after all (the verification pass above was
wrong), say so explicitly and leave that one alone rather than changing correct code.

Report back: for each numbered item above, what you did (fixed / left alone + why).
`, { label: 'fix-confirmed', phase: 'Fix' })
}

// ---------------------------------------------------------------------------
// Build verification
// ---------------------------------------------------------------------------
phase('Build')
const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    buildSucceeded: { type: 'boolean' },
    unitTestsSucceeded: { type: 'boolean' },
    summary: { type: 'string' },
    remainingIssues: { type: 'array', items: { type: 'string' } },
  },
  required: ['buildSucceeded', 'unitTestsSucceeded', 'summary'],
}

let verify = await agent(`
Final verification pass on the FlameRealms plugin project (package ${PACKAGE}) in the current
directory, after a bug-fix pass. Fix report:
"""${fixReport === null ? '(no confirmed bugs — nothing was changed)' : (typeof fixReport === 'string' ? fixReport : JSON.stringify(fixReport))}"""

1. Run the build (./gradlew build if the wrapper exists, otherwise gradle build). Read the
   full error output if it fails.
2. If there are compile errors introduced by the fix pass, fix them directly.
3. Once it compiles, run the unit test task (skip/exclude the Docker-dependent integration
   test explicitly if Docker isn't available in this sandbox — note whether it was available).
4. Report exactly what still doesn't work, if anything.
`, { schema: VERIFY_SCHEMA, label: 'verify', phase: 'Build' })

let fixAttempts = 0
while (!verify.buildSucceeded && fixAttempts < 2) {
  fixAttempts++
  log(`Build still failing after the bug-fix pass, fix attempt ${fixAttempts}/2...`)
  verify = await agent(`
The FlameRealms build is still failing after the bug-fix pass. Its report:
"""${JSON.stringify(verify)}"""

Fix the remaining compile/build errors directly, then re-run the build and unit tests, and
report the same structured result again.
`, { schema: VERIFY_SCHEMA, label: `verify-fix-${fixAttempts}`, phase: 'Build' })
}

log(verify.buildSucceeded
  ? `Build succeeded. Unit tests ${verify.unitTestsSucceeded ? 'passed' : 'did NOT pass'}.`
  : `Build still failing after the bug-fix pass — see remainingIssues.`)

return { confirmed, fixReport, verify, fixAttempts }
