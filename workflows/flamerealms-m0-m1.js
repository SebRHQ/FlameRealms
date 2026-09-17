// FlameRealms — M0 + M1 implementation workflow
//
// Invoke from a fresh chat (no prior conversation context required) with:
//   Workflow({ scriptPath: "workflows/flamerealms-m0-m1.js" })
//
// Scope: TECHNICAL_SPEC.md v0.4, milestones M0 (Bootstrap) and M1 (Realm core) ONLY.
// Nothing here implements economy, claims, nexus, war, or any later milestone —
// that is deliberate milestone discipline, not an oversight. AsyncDatabaseExecutor
// is stood up here (in M0) rather than at M2 as the milestone table loosely implied,
// because M1's RealmService already performs DB writes that must never block the
// Paper main thread — this is a correction of an ordering gap in the spec text,
// not a scope expansion.
//
// Every stage prompt below is self-contained: it does NOT ask the agent to read
// PROJECT.md/TECHNICAL_SPEC.md (thousands of lines combined) — the exact schema,
// interfaces, and constraints each stage needs are embedded directly, to keep
// token usage down across ~7 agents.
//
// Two corrections from the original draft, per explicit product direction:
//   - The invite-consuming command is "/realm accept <realmName>", not "/realm join
//     <realmName>" — it only ever succeeds against a pending invite, there is no public
//     open-join in M1.
//   - "/realm disband" IS implemented in this workflow (it was missing from the command
//     layer even though RealmServiceImpl already had disbandRealm()). Leadership TRANSFER
//     (handing a realm to another player without disbanding it) is explicitly NOT
//     implemented here — it's left as a documented TODO for a later task, since disband
//     being the only way to give up a realm is a known, deliberately-deferred gap.
//
// Working directory is assumed to be the FlameRealms repo root, which already
// contains PROJECT.md, TECHNICAL_SPEC.md, TODO.md, CHANGELOG.md, SECURITY.md,
// README.md, .gitignore, and an empty src/ directory. Do not modify those docs.

export const meta = {
  name: 'flamerealms-m0-m1',
  description: 'Bootstrap the FlameRealms Paper plugin (Gradle/HikariCP/Flyway/async DB) and implement Realm core (M0+M1)',
  whenToUse: 'Run this to scaffold the FlameRealms plugin project and implement Realm creation/membership/ranks, per TECHNICAL_SPEC.md v0.4 milestones M0 and M1.',
  phases: [
    { title: 'Scaffold', detail: 'Gradle project, plugin.yml, config.yml, CI' },
    { title: 'Persistence infra', detail: 'HikariCP, Flyway wiring, async DB executor' },
    { title: 'Domain & DAO', detail: 'Realm/RealmMember/RealmRank + migration + DAOs' },
    { title: 'Service & cache', detail: 'RealmService, RealmCache, default rank seeding' },
    { title: 'Commands & tests', detail: '/realm commands + unit/integration tests' },
    { title: 'Verify', detail: 'Build, fix compile errors, produce final report' },
  ],
}

const PACKAGE = 'com.flamerealms'

// ---------------------------------------------------------------------------
// Stage 1 — Scaffold
// ---------------------------------------------------------------------------
phase('Scaffold')
const scaffold = await agent(`
You are bootstrapping a brand-new Paper/Purpur Minecraft plugin project called FlameRealms,
Java 25, Gradle (Kotlin DSL). Work in the current directory, which is the repo root and
already contains PROJECT.md, TECHNICAL_SPEC.md, TODO.md, CHANGELOG.md, SECURITY.md,
README.md, .gitignore, and an empty src/ directory — do not modify or move those docs.
You do NOT need to read PROJECT.md or TECHNICAL_SPEC.md; everything required is below.

Create exactly this, nothing more (no Realm/economy/domain logic yet — that's later stages):

1. settings.gradle.kts — rootProject.name = "FlameRealms".

2. build.gradle.kts (Kotlin DSL):
   - Java toolchain: languageVersion = JavaLanguageVersion.of(25)
   - plugins: java, and the shadow plugin — use "com.gradleup.shadow" (the maintained fork;
     do NOT use the old "com.github.johnrengelman.shadow", it's unmaintained), latest 8.x.
   - repositories: mavenCentral(), and papermc's repo (https://repo.papermc.io/repository/maven-public/).
   - dependencies:
     - compileOnly("io.papermc.paper:paper-api:<latest stable 1.21.x>") — pick the latest
       stable Paper API version you're confident exists; note the exact version you chose
       in your final summary so it can be corrected later if needed.
     - implementation for: HikariCP (com.zaxxer:HikariCP, latest 5.x), Flyway
       (org.flywaydb:flyway-core + org.flywaydb:flyway-mysql, latest 10.x), and a MariaDB
       JDBC driver (org.mariadb.jdbc:mariadb-java-client, latest 3.x) — these three will be
       shaded into the plugin jar.
     - testImplementation: JUnit 5 (org.junit.jupiter:junit-jupiter, latest 5.x), Mockito
       (org.mockito:mockito-core), AssertJ (org.assertj:assertj-core). testRuntimeOnly:
       org.junit.platform:junit-platform-launcher.
   - tasks.test { useJUnitPlatform() }
   - shadowJar configuration: relocate the three shaded libraries into
     "${PACKAGE}.libs.<original>" (e.g. com.zaxxer.hikari -> ${PACKAGE}.libs.hikari) to avoid
     classpath collisions with other plugins on the same server.
   - No ORM, no other dependencies.

3. gradle.properties — reasonable defaults (org.gradle.jvmargs with adequate heap).

4. Gradle wrapper: check if a system \`gradle\` binary is available (\`which gradle\`). If yes,
   run \`gradle wrapper --gradle-version 8.10\` to generate gradlew/gradlew.bat/gradle-wrapper.jar/
   gradle-wrapper.properties. If no system Gradle is available, do NOT fabricate a wrapper —
   state this limitation clearly in your final summary instead.

5. src/main/resources/plugin.yml:
   - name: FlameRealms, main: ${PACKAGE}.FlameRealmsPlugin, version: '\${project.version}' style
     placeholder is fine, api-version matching the Paper API version you chose.
   - depend: [WorldGuard]  (hard dependency — required, per spec, for spawn/PvP protection;
     do not make it a softdepend)
   - softdepend: [Vault, PlaceholderAPI, ItemsAdder, CustomNamePlates, WorldEdit]

6. src/main/resources/config.yml — minimal, just:
   - a 'database:' block (host, port, database, username, password placeholders, pool-size)
   - an 'async:' block (executor thread-pool size)
   - a comment noting that pricing.yml/war.yml/etc. arrive in later milestones — do not create
     those files now.

7. src/main/java/${PACKAGE.replaceAll('.', '/')}/FlameRealmsPlugin.java — the main plugin
   class extending JavaPlugin, onEnable()/onDisable() with TODO comments marking exactly
   where DatabaseManager/AsyncDatabaseExecutor will be wired in (next stage adds the real code).

8. .github/workflows/build.yml — checkout, actions/setup-java@v4 with distribution 'temurin'
   and java-version '25', then run './gradlew build' (or 'gradle build' if no wrapper exists —
   match whatever you actually produced in step 4).

Report back: exact Paper API version chosen, whether the Gradle wrapper was actually
generated (yes/no and why), and the full list of files you created.
`, { label: 'scaffold', phase: 'Scaffold' })

// ---------------------------------------------------------------------------
// Stage 2 — Persistence infrastructure (HikariCP, Flyway, async executor)
// ---------------------------------------------------------------------------
phase('Persistence infra')
const persistence = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
A previous stage created the Gradle project, plugin.yml, config.yml, and a stub
FlameRealmsPlugin.java. Its report was:

"""
${typeof scaffold === 'string' ? scaffold : JSON.stringify(scaffold)}
"""

Now add the persistence infrastructure. This is the ONE most important structural rule in
the whole project, so follow it exactly: **no database call is ever allowed to block the
Paper main thread.** Concretely:

1. ${PACKAGE}.config.DatabaseConfig — a small immutable record binding config.yml's
   'database:' block (host, port, database, username, password, pool-size) and an
   'async:' block (executor pool size), loaded from Bukkit's FileConfiguration.

2. ${PACKAGE}.persistence.DatabaseManager:
   - Owns a HikariDataSource built from DatabaseConfig (JDBC URL for MariaDB).
   - On construction/startup, runs Flyway against 'classpath:db/migration' and calls
     .migrate() — this happens once, synchronously, during plugin startup (onEnable),
     which is acceptable; it is NOT the same as blocking the main thread during normal
     gameplay ticks, which is what must never happen.
   - Exposes the DataSource (package-private/internal is fine) for AsyncDatabaseExecutor
     to draw connections from.
   - shutdown() closes the HikariCP pool.

3. ${PACKAGE}.persistence.AsyncDatabaseExecutor:
   - A bounded java.util.concurrent.ExecutorService (fixed thread pool, size from
     DatabaseConfig, default small e.g. 4) — this is where EVERY database transaction in
     the whole plugin will run, from this point forward, for the rest of the project.
   - A method roughly shaped like:
     <T> CompletableFuture<T> submit(java.util.function.Function<java.sql.Connection, T> work)
     — borrows a Connection from DatabaseManager's DataSource, runs 'work' on the async
     executor thread (never the caller's thread), wraps it as a CompletableFuture, and
     ensures the connection is returned/closed (try-with-resources) whether 'work'
     succeeds or throws.
   - A shutdown(java.time.Duration timeout) method that stops accepting new work and
     awaits termination up to the timeout — this is what a graceful plugin onDisable()
     calls to drain in-flight work before the DataSource is closed.
   - Add a clear Javadoc/comment at the top of this class stating: "No DAO or service
     method may open a JDBC connection or run a query on any thread other than one
     supplied by this executor. If you are tempted to call a DAO method directly from a
     Bukkit event handler or command executor, you are doing it wrong — dispatch through
     AsyncDatabaseExecutor.submit(...) and hop back to the main thread via
     Bukkit.getScheduler().runTask(...) to apply the result."

4. Create the (currently empty) directory src/main/resources/db/migration/ — the first
   real migration file is added by the next stage, not this one.

5. Wire DatabaseManager and AsyncDatabaseExecutor into FlameRealmsPlugin.onEnable()
   (construct in the right order: config load -> DatabaseManager (runs migrations) ->
   AsyncDatabaseExecutor) and onDisable() (AsyncDatabaseExecutor.shutdown(...) BEFORE
   DatabaseManager.shutdown(), so in-flight work finishes before the pool closes).

6. If HikariCP/Flyway/MariaDB-driver dependencies are somehow missing from
   build.gradle.kts, add them now (they should already be there from the previous stage).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created/edited,
and confirm the onEnable/onDisable ordering you implemented.
`, { label: 'persistence-infra', phase: 'Persistence infra' })

// ---------------------------------------------------------------------------
// Stage 3 — Domain model, Flyway migration, DAO layer
// ---------------------------------------------------------------------------
phase('Domain & DAO')
const domainDao = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
Persistence infrastructure (DatabaseManager, AsyncDatabaseExecutor) now exists. Its report:

"""
${typeof persistence === 'string' ? persistence : JSON.stringify(persistence)}
"""

Now implement the M1 "Realm core" data model — and ONLY M1's scope. Do not add balance,
treasury, nexus, friendly-fire, specialization, or power columns/fields — those belong to
later milestones (M2/M4/M7/M12) and will be added by future ALTER TABLE migrations. Keeping
M1 tightly scoped like this is a deliberate project rule, not an oversight.

1. Flyway migration src/main/resources/db/migration/V1__realm_core.sql, exactly:

CREATE TABLE realms (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(32)  NOT NULL UNIQUE,
  display_name  VARCHAR(48)  NOT NULL,
  leader_uuid   BINARY(16)   NOT NULL,
  level         INT UNSIGNED NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL,
  disbanded_at  DATETIME     NULL,
  INDEX idx_leader (leader_uuid)
);

CREATE TABLE realm_ranks (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_id      BIGINT UNSIGNED NOT NULL,
  name          VARCHAR(24)  NOT NULL,
  priority      INT          NOT NULL,
  permissions   BIGINT UNSIGNED NOT NULL,
  is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
  UNIQUE KEY uq_realm_rank_name (realm_id, name),
  FOREIGN KEY (realm_id) REFERENCES realms(id) ON DELETE CASCADE
);

CREATE TABLE realm_members (
  realm_id      BIGINT UNSIGNED NOT NULL,
  player_uuid   BINARY(16)   NOT NULL,
  rank_id       BIGINT UNSIGNED NOT NULL,
  joined_at     DATETIME     NOT NULL,
  PRIMARY KEY (realm_id, player_uuid),
  UNIQUE KEY uq_player_one_realm (player_uuid),
  FOREIGN KEY (realm_id) REFERENCES realms(id) ON DELETE CASCADE,
  FOREIGN KEY (rank_id) REFERENCES realm_ranks(id)
);

(uq_player_one_realm is load-bearing: it enforces "a player belongs to at most one realm"
at the database level, not just in application code.)

2. Domain records (${PACKAGE}.domain package), plain immutable Java records, no Bukkit
   imports, no JDBC types leaking through:
   - Realm(long id, String name, String displayName, UUID leaderUuid, int level,
     Instant createdAt, Instant disbandedAt)
   - RealmRank(long id, long realmId, String name, int priority, long permissions, boolean isDefault)
   - RealmMember(long realmId, UUID playerUuid, long rankId, Instant joinedAt)
   - RealmPermission — an enum with one bit per permission: INVITE, KICK, CLAIM, UNCLAIM,
     DEPOSIT, WITHDRAW, MANAGE_RANKS, DIPLOMACY, DECLARE_WAR, MANAGE_NEXUS (each a distinct
     power-of-two long), plus a static helper 'boolean has(long mask, RealmPermission perm)'
     and a static 'long ALL' constant combining every bit. (Most of these permissions aren't
     used by any feature yet — they're defined now because the bit layout is already decided
     project-wide and shouldn't be redefined per-milestone.)

3. DAO interfaces (${PACKAGE}.persistence.dao package) — every method takes a
   java.sql.Connection as its first parameter (these are composed together by the service
   layer inside ONE AsyncDatabaseExecutor.submit call per logical operation, so multi-step
   operations like "create realm + seed 3 ranks + insert leader membership" happen in a
   single transaction):
   - RealmDao: insert(Connection, Realm) -> Realm (with generated id), findById, findByName,
     findByPlayerUuid (join through realm_members), markDisbanded(Connection, long realmId, Instant),
     delete(Connection, long realmId).
   - RealmRankDao: insert(Connection, RealmRank) -> RealmRank, findByRealmAndName,
     findDefaultRank(Connection, long realmId) (is_default = true).
   - RealmMemberDao: insert(Connection, RealmMember), delete(Connection, long realmId, UUID player),
     findByPlayerUuid, updateRank(Connection, long realmId, UUID player, long rankId).

4. JDBC implementations (${PACKAGE}.persistence.jdbc package): JdbcRealmDao, JdbcRealmRankDao,
   JdbcRealmMemberDao — plain PreparedStatement JDBC, no ORM. UUIDs are stored as BINARY(16);
   write a small helper (e.g. ${PACKAGE}.util.UuidCodec with toBytes(UUID)/fromBytes(byte[]))
   since the MariaDB driver does not natively bind java.util.UUID to BINARY(16).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
confirm the exact method signatures you settled on for each DAO interface (the next stage's
RealmService implementation depends on these being stable).
`, { label: 'domain-dao', phase: 'Domain & DAO' })

// ---------------------------------------------------------------------------
// Stage 4 — RealmService, RealmCache, default rank seeding
// ---------------------------------------------------------------------------
phase('Service & cache')
const service = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
Domain records and DAOs now exist. Their report:

"""
${typeof domainDao === 'string' ? domainDao : JSON.stringify(domainDao)}
"""

Now implement:

1. ${PACKAGE}.cache.RealmCache — the in-memory, write-through, authoritative-for-reads
   cache. Backed by ConcurrentHashMap: id -> Realm, lowercased-name -> id,
   player UUID -> realm id. A loadAll(Connection) method to warm-populate it at startup
   (called once during onEnable via AsyncDatabaseExecutor, before the plugin is considered
   ready). Plain get/put/remove accessors used by RealmService after each successful
   commit — the cache is mutated ONLY after a DB transaction commits, never optimistically
   before.

2. ${PACKAGE}.service.RealmService (interface) — exactly these signatures (mutating methods
   are CompletableFuture-returning because they dispatch through AsyncDatabaseExecutor;
   pure cache reads are synchronous since they touch no I/O):

   CompletableFuture<Realm> createRealm(UUID leader, String name);
   CompletableFuture<Void> disbandRealm(long realmId, UUID requestedBy);
   void invite(long realmId, UUID inviter, UUID target);           // see note below — in-memory only
   CompletableFuture<Void> acceptInvite(UUID player, long realmId); // consumes a pending invite — backs "/realm accept"
   CompletableFuture<Void> leave(UUID player);
   CompletableFuture<Void> setRank(long realmId, UUID actor, UUID target, long rankId);
   Optional<Realm> getByPlayer(UUID player);   // synchronous, RealmCache read only
   Optional<Realm> getByName(String name);     // synchronous, RealmCache read only

3. ${PACKAGE}.service.RealmServiceImpl implementing it:
   - createRealm(leader, name): fast-fail pre-check against RealmCache (leader not already
     in a realm; name not already taken) for quick user feedback, then ONE
     AsyncDatabaseExecutor.submit call that: inserts the realm row, seeds exactly 3 default
     ranks — Leader (priority 100, permissions = RealmPermission.ALL, is_default=false),
     Officer (priority 50, permissions = INVITE|KICK|CLAIM|DEPOSIT|DIPLOMACY bits, is_default=false),
     Member (priority 0, permissions = 0, is_default=true) — inserts the leader's
     realm_members row pointing at the Leader rank, all inside that one transaction/connection.
     On success, update RealmCache and complete the future with the created Realm. On a
     unique-constraint failure (race lost), fail the future with a clear domain exception
     rather than a raw SQLException.
   - disbandRealm(realmId, requestedBy): verify requestedBy is that realm's leader_uuid
     (permission check), then one transaction: markDisbanded + delete members/ranks (FK
     cascade handles this once you delete the realms row, or delete explicitly — your
     choice, document which), update cache (remove the realm and all its cached member
     mappings). Note in your summary that full disband semantics (treasury destruction,
     claim release, war guard) are out of scope for M1 and arrive with those later
     milestones — this method is realm/membership-only for now.
   - invite/acceptInvite: **invites are in-memory only for M1, not persisted** — hold
     pending invites as a ConcurrentHashMap<Long, Set<UUID>> (realm id -> invited player
     UUIDs) on RealmService or RealmCache, your choice. invite() just adds to the set (no
     DB, no CompletableFuture needed). acceptInvite(player, realmId) checks the player was
     actually invited, then does the same kind of one-transaction DB write as createRealm's
     member-insert (using the realm's default rank via RealmRankDao.findDefaultRank),
     updates cache, and removes the consumed invite from the pending set. State this
     scoping decision explicitly in your final summary — it's a deliberate M1
     simplification, not a missed requirement (a persisted invites table can be added later
     without breaking this interface).
   - leave(player): reject if the player is that realm's leader_uuid — for now the ONLY way
     for a Leader to give up a realm is disbandRealm(); there is deliberately no ownership-
     transfer method yet (no "make someone else Leader without disbanding"). Return a clear,
     specific error message from leave() saying so. **Do not implement leadership transfer
     in this workflow** — it's tracked as a follow-up (see the FUTURE WORK note in stage 5a
     below) precisely because leave()'s only escape hatch being "disband the whole realm"
     is a real usability gap, just one that's explicitly out of scope for right now.
     Otherwise, leave() is one transaction deleting the realm_members row, update cache.
   - setRank(realmId, actor, target, rankId): permission check (actor's current rank must
     have MANAGE_RANKS), then update realm_members.rank_id in one transaction, update cache.
   - Every mutating method's cache update happens strictly after its transaction commits —
     never before, never optimistically.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
the exact CompletableFuture-based signatures you implemented (the next stage's command
layer and test suite both depend on these).
`, { label: 'realm-service', phase: 'Service & cache' })

// ---------------------------------------------------------------------------
// Stage 5 — Commands + Tests (independent of each other, both depend only on stage 4)
// ---------------------------------------------------------------------------
phase('Commands & tests')
const [commands, tests] = await parallel([
  () => agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
RealmService/RealmServiceImpl/RealmCache now exist. Their report:

"""
${typeof service === 'string' ? service : JSON.stringify(service)}
"""

Implement ${PACKAGE}.command.realm.RealmCommand using Paper's native Brigadier command API
(io.papermc.paper.command.brigadier.Commands / CommandSourceStack) — do NOT add any
third-party command framework dependency. Register it in FlameRealmsPlugin.onEnable() via:

  this.getLifecycleManager().registerEventHandler(
      io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
      event -> event.registrar().register(buildRealmCommand(), "Realm management"));

Subcommands, all under /realm:
  - create <name>        — calls RealmService.createRealm(player, name)
  - info [name]          — with no arg, shows the executing player's own realm (via
                           getByPlayer); with an arg, looks up by name (getByName). Not
                           found -> clear message.
  - invite <player>      — resolves the online target player, calls RealmService.invite(...)
                           (permission check: actor must have the INVITE bit in their rank)
  - accept <realmName>   — calls RealmService.acceptInvite(player, realmId resolved from
                           name). NOTE: this is intentionally named "accept", not "join" —
                           it only succeeds if the player has a pending invite; there is no
                           public/open-join command in M1.
  - leave                — calls RealmService.leave(player)
  - disband              — DESTRUCTIVE. Only the realm's leader_uuid may run it (check via
                           getByPlayer(player) + compare leaderUuid before calling the
                           service at all, as a fast main-thread rejection, in addition to
                           whatever check disbandRealm() itself does). Require a two-step
                           confirmation: "/realm disband" alone shows a warning ("this
                           deletes the realm and its treasury permanently, run
                           /realm disband confirm to proceed") and does NOT call the
                           service; only "/realm disband confirm" actually calls
                           RealmService.disbandRealm(realmId, player).

FUTURE WORK — do NOT implement now, just leave a clear '// TODO(ownership-transfer):' code
comment near RealmCommand's disband/leave handling and in RealmServiceImpl's leave() method:
a future '/realm transfer <player>' command (and a corresponding
RealmService.transferLeadership(realmId, currentLeader, newLeader) method) should let a
Leader hand the realm over to another member without disbanding it. Right now disband is
the ONLY way a Leader can give up a realm, which is a known, deliberately-deferred gap —
make sure the TODO comment says exactly that, so it isn't mistaken for an oversight later.

Since RealmService's mutating methods return CompletableFuture<...>, the command handler
must NOT block the main thread waiting on the future. Use .thenAccept(...)/.exceptionally(...)
and hop back to the main thread with Bukkit.getScheduler().runTask(plugin, () -> ...) before
sending any message to the player or touching Bukkit API. This is a hard rule, not a style
preference — re-check every subcommand you write against it before finishing.

Use Adventure/MiniMessage (net.kyori.adventure.text.minimessage.MiniMessage) for every
player-facing message — Paper ships this natively, no extra dependency needed. Keep messages
simple and clear for now; a full messages.yml/lang system is a later milestone, not this one.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
confirm each subcommand correctly defers Bukkit API access to the main thread after the
future completes.
`, { label: 'realm-commands', phase: 'Commands & tests' }),

  () => agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
RealmService/RealmServiceImpl/RealmCache and the DAO layer now exist. Service report:

"""
${typeof service === 'string' ? service : JSON.stringify(service)}
"""
Domain/DAO report:
"""
${typeof domainDao === 'string' ? domainDao : JSON.stringify(domainDao)}
"""

Add the M1 test suite. If JUnit 5 / Mockito / AssertJ aren't already wired into
build.gradle.kts's testImplementation (they should be from the scaffold stage), add them,
plus testRuntimeOnly("org.junit.platform:junit-platform-launcher"), and confirm
tasks.test { useJUnitPlatform() } is present.

1. Unit tests for RealmServiceImpl, using hand-written IN-MEMORY FAKE implementations of
   the RealmDao/RealmRankDao/RealmMemberDao interfaces (simple HashMap-backed fakes — NOT
   Mockito mocks, NOT a real database) so these tests run in milliseconds with no Docker/DB
   dependency. Cover at least:
   - createRealm succeeds: realm row created, exactly 3 ranks seeded with the right
     permission bitmasks, leader gets a realm_members row with the Leader rank.
   - createRealm rejects a player who is already in a realm.
   - createRealm rejects a duplicate realm name.
   - leave() rejects when the caller is the realm's leader.
   - setRank() rejects when the actor's rank lacks MANAGE_RANKS.
   - invite()+acceptInvite() happy path (in-memory invite is honored, DB member row
     created); acceptInvite() rejects a player who was never invited.
   - disbandRealm() succeeds when called by the actual leader (realm/ranks/members gone
     afterward, cache cleared); disbandRealm() rejects when requestedBy is not the leader.

2. A Testcontainers-based DAO integration test (e.g. JdbcRealmDaoIT) that spins up a
   MariaDB container, runs the real V1__realm_core.sql Flyway migration against it, and
   exercises JdbcRealmDao/JdbcRealmMemberDao directly — specifically asserting the two
   database-level invariants: inserting a second realm_members row for a player who already
   has one fails (uq_player_one_realm), and inserting a second realm with a duplicate name
   fails (realms.name UNIQUE). Tag this test with a JUnit5 @Tag("integration") and exclude
   it from the default 'test' task (e.g. via a Gradle test filter or a separate task) so
   './gradlew test' passes even in a sandbox without Docker — document in a comment how to
   run the integration test where Docker IS available (e.g. './gradlew integrationTest' or
   equivalent, your choice of exact task name, just be consistent and document it).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
explicitly confirm whether you were able to actually run the fake-DAO unit tests in this
environment and whether they passed.
`, { label: 'realm-tests', phase: 'Commands & tests' }),
])

// ---------------------------------------------------------------------------
// Stage 6 — Build verification and fix-up
// ---------------------------------------------------------------------------
phase('Verify')
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
Final verification pass on the FlameRealms plugin project (package ${PACKAGE}) in the
current directory, covering everything built across the M0+M1 workflow so far:

Scaffold report: """${typeof scaffold === 'string' ? scaffold : JSON.stringify(scaffold)}"""
Persistence report: """${typeof persistence === 'string' ? persistence : JSON.stringify(persistence)}"""
Domain/DAO report: """${typeof domainDao === 'string' ? domainDao : JSON.stringify(domainDao)}"""
Service report: """${typeof service === 'string' ? service : JSON.stringify(service)}"""
Commands report: """${typeof commands === 'string' ? commands : JSON.stringify(commands)}"""
Tests report: """${typeof tests === 'string' ? tests : JSON.stringify(tests)}"""

Do the following, in order:
1. Run the build (./gradlew build if the wrapper exists, otherwise gradle build — check
   which is actually present first). Read the full error output if it fails.
2. If there are compile errors, fix them directly — you have full Read/Edit/Bash access to
   the whole project. Common cross-stage seams to check first: DAO method signatures
   actually matching what RealmServiceImpl calls, RealmCache method names matching what
   RealmServiceImpl/RealmCommand call, and CompletableFuture usage being consistent
   (service returns CompletableFuture, command layer consumes it without blocking the main
   thread).
3. Once it compiles, run the unit test task (NOT the Docker-dependent integration test —
   exclude/skip that one explicitly if running it would hang or fail for lack of Docker;
   note whether Docker was actually available in this environment).
4. Report exactly what still doesn't work, if anything (e.g. "Gradle wrapper could not be
   generated earlier, required a system Gradle install to build", "integration test not
   run in this sandbox, verify manually where Docker is available", or any other honest
   caveat) — do not claim something works if you didn't actually verify it.

Do not read PROJECT.md or TECHNICAL_SPEC.md unless you hit something genuinely ambiguous
that the reports above don't resolve.
`, { schema: VERIFY_SCHEMA, label: 'verify', phase: 'Verify' })

let fixAttempts = 0
while (!verify.buildSucceeded && fixAttempts < 2) {
  fixAttempts++
  log(`Build still failing, fix attempt ${fixAttempts}/2...`)
  verify = await agent(`
The FlameRealms build is still failing after a previous attempt. Its report:
"""${JSON.stringify(verify)}"""

Fix the remaining compile/build errors directly (Read/Edit/Bash access to the whole
project), then re-run the build and the unit test task, and report the same structured
result again (buildSucceeded, unitTestsSucceeded, summary, remainingIssues).
`, { schema: VERIFY_SCHEMA, label: `verify-fix-${fixAttempts}`, phase: 'Verify' })
}

log(verify.buildSucceeded
  ? `Build succeeded after ${fixAttempts} fix attempt(s). Unit tests ${verify.unitTestsSucceeded ? 'passed' : 'did NOT pass'}.`
  : `Build still failing after ${fixAttempts} fix attempt(s) — see remainingIssues.`)

return { ...verify, fixAttempts }
