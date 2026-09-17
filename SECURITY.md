# FlameRealms — Security & Integrity Policy

FlameRealms manages real player-facing currency, unique claims on world-space, and multi-party state (wars, diplomacy, treasuries). This document states the guarantees the implementation must uphold and the threat model it's designed against. See [TECHNICAL_SPEC.md](TECHNICAL_SPEC.md) for the full architecture; this file is the condensed, security-focused view of the same design.

## Financial integrity guarantees

- Money is represented as integer minor units (cents), never `double`, anywhere inside FlameRealms. The only floating-point conversion happens at the Vault API boundary, if Vault integration is enabled (see TECHNICAL_SPEC.md § Economy Architecture).
- Every balance-changing operation (player wallet or realm treasury) is synchronously durable and transactional. No financial write is ever "fire and forget."
- Every committed financial mutation has exactly one corresponding audit ledger row (`transactions`), written in the same database transaction as the balance change — an unaudited financial mutation is treated as a bug, not an acceptable edge case.
- A realm can never spend more treasury than it holds; a player can never spend more wallet balance than they hold. Enforced by conditional (`WHERE balance >= amount`) updates, never by read-then-write logic that could race.
- Transfers conserve total money; only explicitly-tagged faucet/sink operations change the system-wide total. This is a tested property (see TECHNICAL_SPEC.md § Testing Audit), not just a design intent.

## Crash and restart safety

- All timers (war phases, shield duration, cooldowns) are stored as absolute timestamps, never as in-memory countdowns — a crash or restart never desynchronizes a timer.
- On startup, all realm, claim, treasury, shield, war, and diplomacy state is fully reloaded from the database before the plugin accepts any command.
- On graceful shutdown, any pending non-financial write is drained before the process exits; financial writes are never pending at rest because they are synchronous.
- The server's auto-restart tooling (UltimateAutoRestart) must invoke a graceful stop, not a hard process kill, so this shutdown path actually runs — see TODO.md.

## Concurrency safety

- Unique database constraints are the backstop against duplicate claims and duplicate realm memberships — enforced at the schema level, not only in application code.
- Cross-entity money movement (anything touching two realms, or a realm and a player) locks both sides in a fixed, deterministic order to make deadlock structurally impossible.
- Conditional/guarded updates (`UPDATE ... WHERE status = 'OPEN'`, `WHERE state = 'ACTIVE'`) make state transitions — contract fulfillment, project completion, war-state advancement — fire at most once, even under a race.
- A per-player, per-command in-flight lock rejects true duplicate command submissions (double-clicked GUI buttons, macros) before they reach the transactional layer.

## Permission model

- Realm-level permissions (invite, claim, withdraw treasury, declare war, etc.) are evaluated entirely inside FlameRealms's own per-realm rank system and are independent of the server's global permission plugin (LuckPerms). Realm ranks are never mapped to LuckPerms groups or contexts.
- Server-wide admin/command permissions use ordinary Bukkit permission nodes (`flamerealms.admin.*`, `flamerealms.command.*`), manageable by whichever permission plugin the server operator runs — FlameRealms does not depend on LuckPerms specifically for this.

## Data handled

- Player identity is tracked by Minecraft UUID only. FlameRealms does not handle, store, or enforce authentication credentials, passwords, session tokens, or login state of any kind. Authentication (AuthMe) runs on the Velocity proxy, not the backend Purpur server FlameRealms runs on — by the time any connection reaches FlameRealms, the proxy has already authenticated it. Backend game logic, including every FlameRealms listener, simply assumes a connected player has already passed that boundary; there is no backend-side authentication gate for FlameRealms to implement or rely on, and it introduces no dependency on AuthMe.
- No personal data beyond in-game identifiers (UUID, in-game name) and gameplay state (balances, claims, ranks, chat/command usage relevant to moderation) is stored.

## PvP and war-fairness integrity

FlameRealms is a diplomatic PvP-gating system, not a global PvP toggle (see TECHNICAL_SPEC.md § PvP & Diplomacy Precedence Model — a 7-layer precedence chain). Guarantees that follow directly from that design and are treated as security-relevant, not just gameplay-relevant:

- **WorldGuard is absolute.** Nothing in FlameRealms — including an active war's PvP override — can ever re-enable combat in a WorldGuard-protected region. This is the outermost, non-negotiable layer.
- **Same-Realm friendly fire and Realm-vs-Realm diplomacy are independent controls.** A Realm being at war with a third party never enables or disables its own members' ability to fight each other — friendly fire is a separate, per-Realm setting (off by default), evaluated in a context (same Realm, own territory) that structurally cannot overlap with the war-override context (two different Realms).
- **ALLIANCE is PvP-blocking, same as PEACE and TRUCE.** Allied Realms cannot fight each other inside Realm territory.
- **No personal-toggle loophole out of a real war.** A player cannot use PeacefulToggle to become unkillable inside a war their own Realm is actively fighting — the War-state layer explicitly overrides it, and only it.
- **No last-minute ringer.** A player cannot gear up as a Nomad, join a Realm immediately before a declared war, and instantly become a war combatant — the New Member War Lock denies them the War-state override entirely (not just scoring credit) until their Realm membership tenure (and, optionally, a minimum Contribution) clears a configurable threshold. Nomad gameplay itself is entirely unaffected by this rule.
- **Force-Imbalance resistance to alt-padding.** "Effective combatant" status for the War Force Imbalance system (including the formally-modeled Nexus Capture mechanic) requires recent, real war-relevant action or actual presence in the capture zone, not mere presence elsewhere or idle alts — a side cannot cheaply inflate its counted strength with throwaway accounts.

## Async persistence and financial integrity

All blocking database I/O runs off the Paper main thread (a bounded async executor backed by HikariCP), never inline in a game-event handler. This is a performance correction, not a weakening of any guarantee: every financial or claim-owning operation is still one atomic, durable database transaction — balance mutation and audit-ledger entry still commit together, cross-entity transfers still lock in a fixed deterministic order, and a player is never told an operation succeeded before its transaction has actually committed. Only genuinely non-financial state (e.g. live Nexus-capture progress) is write-behind, and even that is fully flushed on every graceful shutdown, with only a small, bounded loss window on an actual crash.

## Known accepted risks

- **Alt-account faucet farming.** Diminishing-returns payout curves and per-player faucet limits reduce, but cannot fully eliminate, multi-accounting abuse on a server without hardware/account verification. Treated as an accepted, monitored risk (see TECHNICAL_SPEC.md § Specification Issues, exploit analysis) rather than something the plugin can solve outright.
- **Active Population upkeep gaming.** A realm could attempt to suppress its counted active population (and thus its upkeep) by routing activity through a small subset of members. Mitigated by requiring genuine presence *or* genuine Contribution activity (not both easy to fake at once), and by a saturating cost curve, but not eliminated outright — the same category of accepted, monitored risk as alt-account faucet farming.
- **Packet-level libraries present but unused.** ProtocolLib and PacketEvents are installed on the server for other plugins' use; FlameRealms does not use packet manipulation for any feature — including its own particle-based territory visualization, which uses vanilla Paper APIs — and therefore introduces no packet-level attack surface of its own.
- **Third-party plugin conflicts.** Essentials ships its own Vault economy provider, which conflicts with FlameRealms's own if both are Vault-registered simultaneously (see TECHNICAL_SPEC.md § Specification Issues, SPEC-012). FlameRealms owns the economy outright and there is no meaningful existing Essentials balance data to migrate, but the provider-conflict risk is still operational, not automatic — it must be resolved by disabling Essentials' Vault hook before FlameRealms's economy goes live, tracked in TODO.md, not silently worked around in code.
- **Hot-reload risk via PlugManX.** The server runs a plugin manager capable of reloading plugins without a full restart. FlameRealms holds a database connection pool, in-memory write-through caches, and scheduled tasks — reloading it this way risks orphaned connections, duplicate listeners, or stale scheduled tasks. FlameRealms should be excluded from PlugManX's reload targets; a full graceful restart is the supported way to pick up a code update.

## Spawn protection dependency

WorldGuard is a **required** backend dependency (a hard `depend:` in `plugin.yml`, not optional) — it is the absolute, outermost layer of the PvP model (see TECHNICAL_SPEC.md § PvP & Diplomacy Precedence Model, layer 1) and handles all spawn/admin-region protection. FlameRealms implements no fallback spawn-protection logic of its own and will not treat its PvP guarantees as complete on a server without WorldGuard installed and configured.

## Operational recommendations

- Run FlameRealms's database connection under a dedicated, least-privilege MariaDB user scoped only to the schema it owns — not a shared admin credential.
- If the database is not on localhost, use TLS for the connection.
- Database credentials belong in server-local configuration, never committed to version control.
- Back up the database on a schedule independent of world saves — FlameRealms's persistent state (economy, claims, wars) lives entirely in MariaDB, not in any world file, and needs its own backup/restore story.

## Reporting

This is a single-operator project with no public distribution yet. Security-relevant findings during development should be raised directly with the project owner rather than filed publicly, until/unless the project is distributed beyond this server.
