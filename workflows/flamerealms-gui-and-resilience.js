// FlameRealms — DB resilience + expanded config + full chest-GUI layer.
//
// Invoke from a fresh chat with:
//   Workflow({ scriptPath: "workflows/flamerealms-gui-and-resilience.js" })
//
// Prerequisite: M0-M3 already exist (FlameRealmsPlugin, all services, RealmCommand with
// create/info/invite/join/leave/disband/balance/deposit/withdraw/claim/unclaim/map/borders).
//
// Scope, three independent asks bundled into one workflow because they touch overlapping
// files and should land together:
//   1. The plugin must NEVER fail to enable just because the database is unreachable at
//      startup — it must still register every command, and any command that actually needs
//      the database must fail with one clear, specific in-game chat message instead of a
//      crash or a generic error.
//   2. config.yml grows more database tuning knobs (connection timeout, minimum idle,
//      max lifetime, validation timeout) — currently only host/port/pool-size exist.
//   3. A new gui.yml drives a full chest-GUI front end: every /realm action reachable via
//      commands today must also be reachable by clicking an item in a menu, and every menu
//      item's material/display name/lore/slot/chest title must be configurable in gui.yml.
//      The GUI is a second front-end over the EXACT SAME business logic the commands already
//      use — never a reimplementation — so behavior (permission checks, messages, async
//      dispatch) stays identical between /realm withdraw 100 and clicking "Withdraw" -> $100.
//
// Every stage prompt is self-contained: no agent is asked to read PROJECT.md or
// TECHNICAL_SPEC.md (neither document covers this — it's a new ask, not in the original spec).

export const meta = {
  name: 'flamerealms-gui-and-resilience',
  description: 'Add database-outage resilience, expanded DB config, and a fully configurable chest-GUI front end covering every /realm action',
  phases: [
    { title: 'Foundation', detail: 'DB resilience + config.yml (solo) / gui.yml + generic GUI framework (solo), in parallel' },
    { title: 'Actions Refactor', detail: 'Extract RealmCommand\'s business logic into a UI-agnostic RealmActions class' },
    { title: 'Action Screens', detail: 'MainMenu + player/realm selector menus, wired to RealmActions' },
    { title: 'Wiring', detail: 'FlameRealmsPlugin constructs everything, /realm menu command' },
    { title: 'Tests', detail: 'DatabaseUnavailableException + GuiConfig + RealmActions parity tests' },
    { title: 'Verify', detail: 'Build, fix compile errors, produce final report' },
  ],
}

const PACKAGE = 'com.flamerealms'

// ---------------------------------------------------------------------------
// Stage 1a — DB resilience + expanded config.yml (parallel with 1b)
// ---------------------------------------------------------------------------
phase('Foundation')
const [resilience, guiFramework] = await parallel([
  () => agent(`
Continuing work on the existing FlameRealms plugin (package ${PACKAGE}) in the current
directory. Read these files in full first, so your changes match their exact current shape:
  src/main/java/com/flamerealms/persistence/AsyncDatabaseExecutor.java
  src/main/java/com/flamerealms/persistence/DatabaseManager.java
  src/main/java/com/flamerealms/config/DatabaseConfig.java
  src/main/java/com/flamerealms/FlameRealmsPlugin.java

Goal: the plugin must NEVER fail to enable just because the database is unreachable or
migrations fail at startup. Today, FlameRealmsPlugin.onEnable() constructs
"new DatabaseManager(databaseConfig)" directly — if that throws (HikariCP can't connect,
or Flyway can't migrate), the exception propagates out of onEnable() and Paper disables the
whole plugin: no commands work at all, not even ones that don't touch the database. Fix this
so the plugin degrades gracefully instead: it still enables, still registers every command,
and any command that genuinely needs the database fails with ONE clear, specific in-game
message instead of silently doing nothing or throwing something unexpected. Do NOT change
DatabaseManager's own behavior (it should still throw on failure — throwing is correct for a
class whose one job is "connect and migrate, or fail"); the resilience belongs at the level
above it.

1. Add ${PACKAGE}.persistence.DatabaseUnavailableException (a plain unchecked
   RuntimeException, no special fields needed beyond a message) — thrown/used to fail a
   future when the database was never reachable.

2. Change AsyncDatabaseExecutor to support an explicit "unavailable" mode, alongside its
   existing working mode:
   - Add a private boolean field (e.g. "available") and a public static factory
     "public static AsyncDatabaseExecutor unavailable()" that constructs an instance with no
     real DataSource/ExecutorService at all (available=false) — do not touch HikariCP or
     start any thread pool in this path.
   - The existing public constructor (DatabaseManager, int poolSize) sets available=true and
     behaves exactly as it does today.
   - submit(Function<Connection, T> work): if !available, return
     CompletableFuture.failedFuture(new DatabaseUnavailableException(...)) IMMEDIATELY, before
     touching dataSource/executor at all (they may be null in this mode — never dereference
     them). Otherwise, unchanged existing behavior (including the existing
     RejectedExecutionException handling — do not remove or weaken that).
   - shutdown(Duration timeout): must be a safe no-op when available=false (executor may be
     null there) — guard it.

3. In FlameRealmsPlugin.onEnable(), wrap ONLY the construction of DatabaseManager and the
   real AsyncDatabaseExecutor in a try/catch (catch RuntimeException, which covers both
   HikariCP's PoolInitializationException and Flyway's FlywayException — both are unchecked):
   on success, proceed exactly as today; on failure, log at SEVERE level a clear message
   naming the cause (e.g. "Could not connect to the database or run migrations — FlameRealms
   is starting in a degraded mode. Every database-dependent command will show a clear in-game
   error until this is fixed and the server is restarted. Cause: <e.getMessage()>"), set the
   databaseManager field to null, and set asyncDatabaseExecutor to
   AsyncDatabaseExecutor.unavailable(). Every other line in onEnable() (constructing DAOs,
   services, the cache, registering commands, registering the Vault bridge, starting
   ActivityTrackingService/UpkeepService) must run EXACTLY as it does today, unmodified and
   unconditional — none of those constructors touch the database themselves, they only
   dispatch through asyncDatabaseExecutor at call time, so they are all safe to construct even
   in degraded mode. The realm-cache warm-up submit(...) call will simply fail fast and get
   logged by its existing .whenComplete(...) handler — leave that as-is, it already does the
   right thing.
   In onDisable(), asyncDatabaseExecutor.shutdown(...) and databaseManager.shutdown() must
   both remain null-guarded exactly as databaseManager already is today — add the same null
   guard for the degraded-mode case if either could now be null in a way the current code
   doesn't already handle (re-check both: asyncDatabaseExecutor is never null in either mode
   after your change, only databaseManager can be).

4. Do NOT add a "describeError" case for DatabaseUnavailableException yourself —
   RealmCommand.java is being refactored by a separate, later stage of this same workflow and
   will pick up this new exception type from your report below. Do not touch
   RealmCommand.java in this stage.

5. Expand ${PACKAGE}.config.DatabaseConfig with additional HikariCP tuning knobs, read from
   NEW config.yml keys under the existing "database:" block, each defaulting to HikariCP's own
   documented default so behavior does not change for anyone who doesn't set them:
   - database.connection-timeout-ms (long, default 30000) -> HikariConfig.setConnectionTimeout
   - database.minimum-idle (int, default: same value as poolSize, matching HikariCP's own
     default of "minimumIdle == maximumPoolSize unless overridden") -> HikariConfig.setMinimumIdle
   - database.max-lifetime-ms (long, default 1800000) -> HikariConfig.setMaxLifetime
   - database.validation-timeout-ms (long, default 5000) -> HikariConfig.setValidationTimeout
   Read each with the SAME getIntStrict-style "warn on wrong type, don't warn on unset" pattern
   DatabaseConfig already uses (add a getLongStrict sibling for the long-valued ones — same
   logic, just Number#longValue()). None of these four need the requirePositive() treatment
   pool-size/asyncPoolSize get (a bad value here degrades performance, it doesn't crash a
   thread pool constructor) — just read-with-default, no extra validation. Wire all four into
   DatabaseManager.buildDataSource(...)'s HikariConfig construction.
   Update config.yml's "database:" block to document and default all four new keys, matching
   its existing comment style.

6. Add one new messages.yml key: "error-database-unavailable" (same MiniMessage-template style
   as the existing "error-persistence"/"error-not-leader" etc.) — something like "<red>The
   database is currently unavailable. Please try again later or contact an admin.</red>". Do
   not wire it into RealmCommand yet (see point 4) — the later refactor stage does that.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created/edited,
the exact DatabaseUnavailableException package/class name and the exact new
AsyncDatabaseExecutor.unavailable() factory signature (the RealmActions-extraction stage needs
to catch/map this type), and the exact new messages.yml key name.
`, { label: 'db-resilience', phase: 'Foundation' }),

  () => agent(`
Continuing work on the existing FlameRealms plugin (package ${PACKAGE}) in the current
directory. This stage builds a GENERIC, reusable chest-GUI framework and its gui.yml config —
it must know NOTHING about realms/claims/economy specifically (that comes in later stages of
this same workflow). Do NOT touch RealmCommand.java, FlameRealmsPlugin.java, or any existing
file in ${PACKAGE}.service/${PACKAGE}.persistence/${PACKAGE}.domain — only create new files, so
your work never conflicts with the other stage running in parallel with you.

Read src/main/java/com/flamerealms/config/VisualizationConfig.java first — it is the pattern
every config record in this project should follow: read from a FileConfiguration, warn and
fall back to a sane default on a bad/missing value (an enum that doesn't parse, a size out of
range), NEVER throw for a cosmetic config mistake (unlike DatabaseConfig's pool-size, which
intentionally DOES throw — a broken GUI/particle config is not fatal the way a broken thread
pool is; match VisualizationConfig's philosophy here, not DatabaseConfig's).

1. src/main/resources/gui.yml — copied to the plugin's data folder on first run via
   saveResource(...) exactly like config.yml/messages.yml/pricing.yml already are (read
   Messages.java or PricingConfig.java first to match that exact loading convention: never
   overwritten on an existing install, regenerated with defaults if deleted). Every material/
   name/lore/slot/title below must be freely editable by a server admin. Exact starting
   content:

main-menu:
  title: "<dark_gray>FlameRealms</dark_gray>"
  size: 27
  filler-material: GRAY_STAINED_GLASS_PANE
  items:
    info:
      slot: 10
      material: BOOK
      name: "<gold>Realm Info</gold>"
      lore: ["<gray>View your realm's info</gray>"]
    create:
      slot: 11
      material: NETHER_STAR
      name: "<gold>Create Realm</gold>"
      lore: ["<gray>Found a new realm</gray>"]
    invite:
      slot: 12
      material: PLAYER_HEAD
      name: "<gold>Invite Player</gold>"
      lore: ["<gray>Invite an online player</gray>"]
    join:
      slot: 13
      material: WRITABLE_BOOK
      name: "<gold>Join Realm</gold>"
      lore: ["<gray>Browse and join a realm</gray>"]
    leave:
      slot: 14
      material: IRON_DOOR
      name: "<gold>Leave Realm</gold>"
      lore: []
    disband:
      slot: 15
      material: TNT
      name: "<red>Disband Realm</red>"
      lore: ["<gray>This cannot be undone</gray>"]
    balance:
      slot: 16
      material: GOLD_INGOT
      name: "<gold>Balance</gold>"
      lore: []
    deposit:
      slot: 19
      material: HOPPER
      name: "<gold>Deposit</gold>"
      lore: []
    withdraw:
      slot: 20
      material: CHEST
      name: "<gold>Withdraw</gold>"
      lore: []
    claim:
      slot: 21
      material: GRASS_BLOCK
      name: "<gold>Claim Chunk</gold>"
      lore: ["<gray>Claim the chunk you're standing in</gray>"]
    unclaim:
      slot: 22
      material: BARRIER
      name: "<gold>Unclaim Chunk</gold>"
      lore: ["<gray>Release the chunk you're standing in</gray>"]
    map:
      slot: 23
      material: FILLED_MAP
      name: "<gold>Territory Map</gold>"
      lore: []
    borders:
      slot: 24
      material: GLOWSTONE_DUST
      name: "<gold>Toggle Borders</gold>"
      lore: ["<gray>Toggle a persistent outline of your territory</gray>"]
    close:
      slot: 26
      material: BARRIER
      name: "<red>Close</red>"
      lore: []

confirm-menu:
  title: "<dark_gray>Please Confirm</dark_gray>"
  size: 27
  filler-material: GRAY_STAINED_GLASS_PANE
  confirm-slot: 11
  confirm-material: LIME_WOOL
  confirm-name: "<green>Confirm</green>"
  cancel-slot: 15
  cancel-material: RED_WOOL
  cancel-name: "<red>Cancel</red>"

amount-menu:
  title: "<dark_gray>Choose an Amount</dark_gray>"
  size: 27
  filler-material: GRAY_STAINED_GLASS_PANE
  preset-material: GOLD_NUGGET
  preset-name: "<gold><amount></gold>"
  presets-cents: [10000, 50000, 100000, 500000]
  custom-slot: 22
  custom-material: PAPER
  custom-name: "<yellow>Custom Amount (type in chat)</yellow>"
  cancel-slot: 26
  cancel-material: BARRIER
  cancel-name: "<red>Cancel</red>"

list-menu:
  title: "<dark_gray><list-title></dark_gray>"
  size: 54
  filler-material: GRAY_STAINED_GLASS_PANE
  back-slot: 49
  back-material: ARROW
  back-name: "<gray>Back</gray>"

   ("<amount>" in amount-menu's preset-name and "<list-title>" in list-menu's title are
   placeholders substituted at render time by whatever later code uses these configs — you are
   only building the loader/schema here, not the menus that consume "list-menu" yet.)

2. ${PACKAGE}.gui.GuiConfig and its nested config records, parsed from gui.yml with
   warn-and-default on any bad slot (must be 0..size-1), bad size (must be a positive multiple
   of 9, 9..54 — matching Bukkit's chest inventory size constraints), or unrecognized Material
   name (Material.valueOf(name.toUpperCase(Locale.ROOT)), same pattern as
   VisualizationConfig's Particle parsing). Suggested shape (adjust field names/types as
   needed, but keep the four top-level sections separate and match the yaml above):
   - GuiItemConfig(int slot, Material material, String name, List<String> lore)
   - MainMenuConfig(String title, int size, Material fillerMaterial, Map<String, GuiItemConfig> items)
     — "items" keyed by the exact yaml keys above (info/create/invite/... /close); a later
     stage looks these up by that same key, so preserve the keys as-is.
   - ConfirmMenuConfig(String title, int size, Material fillerMaterial, int confirmSlot,
     Material confirmMaterial, String confirmName, int cancelSlot, Material cancelMaterial,
     String cancelName)
   - AmountMenuConfig(String title, int size, Material fillerMaterial, Material presetMaterial,
     String presetName, List<Long> presetsCents, int customSlot, Material customMaterial,
     String customName, int cancelSlot, Material cancelMaterial, String cancelName)
   - ListMenuConfig(String title, int size, Material fillerMaterial, int backSlot, Material
     backMaterial, String backName)
   - GuiConfig(MainMenuConfig mainMenu, ConfirmMenuConfig confirmMenu, AmountMenuConfig
     amountMenu, ListMenuConfig listMenu), with a static "load(JavaPlugin plugin)" factory that
     calls plugin.saveResource("gui.yml", false) then loads
     new File(plugin.getDataFolder(), "gui.yml") via YamlConfiguration, and parses each section.
   An item missing from main-menu's "items" map in a customized gui.yml (an admin deleted it)
   must simply be ABSENT from the returned Map, not crash and not synthesize a default for it —
   a later stage skips rendering an action with no config entry.

3. ${PACKAGE}.gui.ChestGui — an abstract base class implementing org.bukkit.inventory.InventoryHolder,
   giving subclasses a small builder-style protected API to construct one Inventory: something
   like "protected void setItem(int slot, ItemStack item, Runnable onClick)" and
   "protected void fillRemaining(Material material)" (fills every still-empty slot with a
   named, lore-less filler item that has no click handler — clicking it does nothing but is
   still cancelled, see GuiManager below). Store the built Inventory and a
   Map<Integer, Runnable> of slot -> click handler internally; expose
   "public Inventory getInventory()" (the InventoryHolder contract) and a package-visible way
   for GuiManager to look up and invoke a slot's handler.

4. ${PACKAGE}.gui.GuiManager — implements org.bukkit.event.Listener, registered once by
   FlameRealmsPlugin (not in this stage). Tracks each player's currently-open ChestGui
   (Map<UUID, ChestGui>). "public void open(Player player, ChestGui gui)" tracks it and calls
   player.openInventory(gui.getInventory()). An @EventHandler on InventoryClickEvent: if the
   clicking player has one of OUR ChestGuis currently tracked, cancel the ENTIRE event
   unconditionally (event.setCancelled(true)) — both when they click inside the GUI's own
   top inventory and when they click their own inventory while the GUI is open — this is the
   standard, deliberately blunt anti-dupe/anti-item-theft rule every chest-GUI plugin uses;
   players simply cannot rearrange their own inventory while a FlameRealms menu is open, which
   is an accepted, ordinary trade-off. Only when the click landed in the GUI's own top
   inventory (check event.getClickedInventory() == the gui's InventoryView top inventory, or
   compare against gui.getInventory()) do you look up and invoke that slot's Runnable handler,
   wrapped so a handler that throws logs a warning instead of leaking an exception into
   Bukkit's event pipeline. An @EventHandler on InventoryCloseEvent (and one on PlayerQuitEvent
   for safety) removes the player from the tracked map — a GUI staying open across a
   disconnect/reconnect must never leak a stale entry.

5. ${PACKAGE}.gui.ConfirmMenu extends ChestGui — a static factory, e.g.
   "public static ConfirmMenu create(GuiConfig config, Runnable onConfirm, Runnable onCancel)"
   (feel free to add an optional extra lore-lines parameter if it reads more naturally, but
   keep it simple), built entirely from GuiConfig.confirmMenu(): parses title/confirm/cancel
   item name+material via MiniMessage (net.kyori.adventure.text.minimessage.MiniMessage,
   already a dependency — this project uses MiniMessage for messages.yml too), sets the
   confirm slot's click handler to onConfirm.run() (and nothing closes the inventory
   automatically — let the Runnable decide, most callers will want to just let the underlying
   action's own message replace the need to manually close, but calling
   player.closeInventory() from inside onConfirm/onCancel is the caller's job, not this
   class's), same for cancel, fillRemaining(fillerMaterial) for everything else.

6. ${PACKAGE}.gui.AmountMenu extends ChestGui plus ${PACKAGE}.util.MoneyParsing (a NEW small
   public utility class, NOT touching RealmCommand.java) + ${PACKAGE}.util.InvalidAmountException
   (a new small public RuntimeException carrying a String messageKey field + getter, replacing
   what is currently a private nested class inside RealmCommand — do not edit RealmCommand.java
   in this stage; a later stage will delete its private copy and switch to this shared one). Port
   the EXACT parsing algorithm from RealmCommand.java's existing private parseAmountToCents (read
   it there first to copy it precisely, do not redesign it) into
   "public static long parseAmountToCents(String raw) throws InvalidAmountException":
   parse with java.math.BigDecimal (never Double.parseDouble), reject unparseable input
   (messageKey "amount-invalid"), reject more than 2 decimal places
   (parsed.stripTrailingZeros().scale() > 2, messageKey "amount-too-many-decimals"), reject a
   non-positive amount (messageKey "amount-not-positive"), multiply by 100 and
   .setScale(0, RoundingMode.UNNECESSARY).longValueExact(), catching ArithmeticException as
   "amount-too-large". These four messages.yml keys already exist — do not add new ones for
   this, just reuse the exact same key strings.
   AmountMenu's static factory, e.g. "public static AmountMenu create(Plugin plugin, GuiConfig
   config, ChatInputService chatInputService, Consumer<Money> onAmountChosen, Runnable onCancel)"
   (Money is ${PACKAGE}.domain.Money, already exists): one button per entry in
   config.amountMenu().presetsCents(), each closing the inventory and calling
   onAmountChosen.accept(Money.ofCents(preset)) directly; the custom-amount button closes the
   inventory, sends the player a short chat prompt (a plain Component is fine here, e.g.
   "<yellow>Type an amount in chat.</yellow>" via Component.text/MiniMessage — you do not need
   a messages.yml key for this one, it's GUI-internal flavor text) and calls
   chatInputService.prompt(player, raw -> { try amount = MoneyParsing.parseAmountToCents(raw);
   call onAmountChosen.accept(Money.ofCents(amount)); catch InvalidAmountException, send the
   player messages... — actually you do NOT have access to this project's Messages class's
   exact key->Component resolution here without adding a dependency on it; simplest correct
   approach: catch InvalidAmountException and send a plain hardcoded-but-clearly-worded
   MiniMessage error Component built inline (e.g. "<red>Invalid amount, please try again.</red>"
   for any messageKey) rather than depending on Messages — note this simplification explicitly
   in your report so a later stage can upgrade it to route through Messages if desired}); the
   cancel button closes the inventory and calls onCancel.run().

7. ${PACKAGE}.gui.ChatInputService implements org.bukkit.event.Listener — captures a player's
   NEXT chat message as a one-shot text input for a GUI flow (used for "create realm" name
   entry and AmountMenu's custom-amount button). "public void prompt(Player player,
   java.util.function.Consumer<String> onInput)" registers a pending handler for that player's
   UUID in a ConcurrentHashMap. Listen on io.papermc.paper.event.player.AsyncChatEvent (Paper's
   modern chat event, not the deprecated Bukkit one — this project already depends on Paper
   API throughout): if a pending handler exists for the chatting player, remove it, call
   event.setCancelled(true) (so the raw text never actually posts to server chat — it was a
   GUI input, not a real chat message), extract the plain text via
   net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message()),
   and invoke the handler back on the MAIN thread via Bukkit.getScheduler().runTask(plugin, ...)
   — AsyncChatEvent fires off the main thread, and the handler will call into
   RealmActions-style logic that expects to run on the main thread (same
   never-block-the-main-thread discipline as everywhere else in this project, just in the
   other direction: this hops FROM an async event TO the main thread before doing anything
   Bukkit-related). Also listen on PlayerQuitEvent to remove any pending handler for a player
   who disconnects before answering.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and the
exact public API (class names, method signatures) for GuiConfig, ChestGui, GuiManager,
ConfirmMenu, AmountMenu, ChatInputService, and MoneyParsing/InvalidAmountException — later
stages of this workflow (RealmActions extraction, the action-screen menus, and plugin wiring)
all depend on these being stable.
`, { label: 'gui-framework', phase: 'Foundation' }),
])

// ---------------------------------------------------------------------------
// Stage 2 — Extract RealmCommand's business logic into RealmActions
// ---------------------------------------------------------------------------
phase('Actions Refactor')
const actionsRefactor = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
Two things now exist from a prior stage, in parallel:

DB resilience report: """${typeof resilience === 'string' ? resilience : JSON.stringify(resilience)}"""
GUI framework report: """${typeof guiFramework === 'string' ? guiFramework : JSON.stringify(guiFramework)}"""

Read src/main/java/com/flamerealms/command/realm/RealmCommand.java IN FULL before changing
anything — it currently mixes two concerns in one class: (a) Brigadier command-tree wiring and
argument parsing, and (b) the actual business logic for every /realm action (permission
checks, calling RealmService/EconomyService/TreasuryService/ClaimService, building/sending
every message, the async future-continuation/runSync pattern, describeError()'s exception ->
message mapping, and the pendingClaims map backing "claim"/"claim confirm"). A later stage of
this same workflow needs to build a chest-GUI front end that triggers the EXACT SAME business
logic as every command — so that logic must be reachable from something that isn't Brigadier
and doesn't have a CommandContext to pull arguments from.

Your job: extract (b) into a NEW class, ${PACKAGE}.command.realm.RealmActions, completely
UI-framework-agnostic (no Brigadier types anywhere in it — only Player/Audience/UUID/Money/
domain types), leaving RealmCommand as a thin layer that parses Brigadier arguments/resolves
any Bukkit lookups needed for validation and then calls straight into RealmActions. This MUST
be a behavior-preserving refactor: every message sent, every permission check, every async
dispatch/runSync hop, every exception->message mapping must work IDENTICALLY to how it does
today — you are only changing WHO calls the logic, never WHAT it does. Do not "improve" or
restructure the logic itself while moving it; a follow-up diff review will compare behavior
before/after.

RealmActions must hold the same fields RealmCommand currently holds that the logic actually
needs (realmService, economyService, treasuryService, claimService, claimVisualizationService,
territoryMapService, asyncDatabaseExecutor, realmMemberDao, realmRankDao, realmCache,
pricingConfig, messages, plugin for runSync) and expose one public method per action, taking
already-resolved arguments (a Player/Audience and plain values), each doing exactly what the
corresponding RealmCommand execute* method does today end-to-end (including sending every
success/failure message itself — these methods return void or a CompletableFuture<Void> purely
for the caller's own bookkeeping if useful, they are not meant to be composed further):
  - createRealm(Player player, String name)
  - showInfo(Audience audience, Optional<Realm> realmOpt, String requestedName) — port
    sendRealmInfo's body verbatim (it already takes the right shape of arguments)
  - showInfoSelf(Player player) — convenience wrapper: showInfo(player,
    realmService.getByPlayer(player.getUniqueId()), null), for a caller (the GUI) that doesn't
    want to duplicate that lookup
  - invite(Player inviter, Player target) — port executeInvite's body, given target already
    resolved (drop the "is target null/offline" and "inviting yourself" checks that only make
    sense when parsing a raw command argument — a caller with an already-resolved online
    Player target that isn't the inviter has already satisfied those; but DO keep the
    self-invite guard if target could plausibly equal inviter from a caller you don't control —
    your judgment, document whichever you choose)
  - join(Player player, Realm realm) — port executeJoin's body given an already-resolved Realm
  - leave(Player player)
  - disband(Player player)
  - showBalance(Player player) — port executeBalance/sendBalance
  - deposit(Player player, Money amount) — port the post-parsing half of executeDeposit
  - withdraw(Player player, Money amount) — same for executeWithdraw
  - previewClaim(Player player) — port executeClaimPreview's body; the pendingClaims
    ConcurrentHashMap<UUID, PendingClaim> and the PendingClaim record/class itself MOVE to
    RealmActions too (it is action-state, not command-parsing state)
  - confirmClaim(Player player) — port executeClaimConfirm's body
  - unclaim(Player player) — port executeUnclaim's body
  - showMap(Player player) — sends territoryMapService.renderMap(player)'s result, matching
    executeMap
  - toggleBorders(Player player) — port executeBorders's body
  - Component describeError(Throwable ex) — moved from RealmCommand verbatim, PLUS one new
    switch case: DatabaseUnavailableException (${PACKAGE}.persistence.DatabaseUnavailableException,
    from the DB-resilience report above) -> messages.get("error-database-unavailable") (the
    exact key that stage added — confirm it in that report). Keep unwrapCompletion(...) as a
    private static helper here too.
  - Also move parseAmountToCents/InvalidAmountException-handling for deposit/withdraw's
    <amount> parsing: DELETE RealmCommand's own private parseAmountToCents method and its
    private nested InvalidAmountException class, and use the NEW SHARED
    ${PACKAGE}.util.MoneyParsing.parseAmountToCents(...) /
    ${PACKAGE}.util.InvalidAmountException from the GUI-framework report above instead — both
    RealmCommand (for its Brigadier argument parsing, which stays in RealmCommand since it's
    genuinely part of argument validation, not business logic) and RealmActions/AmountMenu
    (later stage) end up sharing this one implementation.

RealmCommand.java, after your refactor, keeps: the class's own constructor (now also
constructing/holding a RealmActions instance, or receiving one — your call, but do NOT
duplicate the field list on both classes if you can avoid it: prefer RealmCommand holding a
single "RealmActions actions" field once RealmActions exists, and only keep constructor
parameters RealmCommand itself still directly needs, such as realmService/realmCache for
argument-resolution lookups like "does this realm/player exist" before delegating), build()
and every build*() Brigadier tree-builder method, hasPermission()/requirePlayer()/runSync()
(these stay in RealmCommand — they're Brigadier/CommandSourceStack-specific and irrelevant to
a future GUI caller, which always already has a concrete Player), and each execute*() method
now becomes: extract Brigadier args, resolve anything that needs Bukkit lookups purely for
"does this argument refer to something real" validation (e.g. resolving the invited player by
name, resolving the realm by name for join, rejecting an offline invite target, rejecting
self-invite) — anything that would need to happen identically for a GUI caller too. Since
this argument-resolution logic doesn't apply to a GUI caller (a GUI player-selector menu only
ever lists players who already satisfy "online and not yourself"; a GUI realm-selector menu
only ever lists realms that already exist), it is correct for it to stay in RealmCommand rather
than moving to RealmActions.

FlameRealmsPlugin.java currently constructs "new RealmCommand(this, realmService,
economyService, treasuryService, claimService, claimVisualizationService, territoryMapService,
asyncDatabaseExecutor, realmMemberDao, realmRankDao, realmCache, pricingConfig, messages)" (read
the actual current call site before assuming this list is exact) — update that call site to
match whatever RealmCommand's new constructor needs, and construct the new RealmActions
instance there too (it will not be exposed anywhere yet — the plugin-wiring stage later in this
workflow adds passing it to GUI classes; for now it only needs to exist and be wired into
RealmCommand so nothing regresses). Do NOT construct or reference anything from the
GUI-framework report in this stage beyond MoneyParsing/InvalidAmountException — the rest (
GuiConfig/ChestGui/GuiManager/ConfirmMenu/AmountMenu/ChatInputService) is wired in by a later
stage.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files
created/edited/deleted, the exact final RealmActions public method list (signatures), and
confirm RealmCommand still compiles conceptually against RealmActions (the next stage depends
on this method list being exactly right).
`, { label: 'actions-refactor', phase: 'Actions Refactor' })

// ---------------------------------------------------------------------------
// Stage 3 — Action screens: MainMenu + player/realm selector menus
// ---------------------------------------------------------------------------
phase('Action Screens')
const actionScreens = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
RealmActions now exists (business logic extracted from RealmCommand); the generic GUI
framework (GuiConfig/ChestGui/GuiManager/ConfirmMenu/AmountMenu/ChatInputService) also exists.
Reports:

RealmActions: """${typeof actionsRefactor === 'string' ? actionsRefactor : JSON.stringify(actionsRefactor)}"""
GUI framework: """${typeof guiFramework === 'string' ? guiFramework : JSON.stringify(guiFramework)}"""

Read both RealmActions.java and every file the GUI-framework report lists before writing
anything, to use their exact real method signatures (not the ones paraphrased above, which may
have changed slightly during implementation).

Build ${PACKAGE}.gui.MainMenu extends ChestGui, plus two small supporting list-style menus,
wiring every /realm action to a chest-GUI trigger. MainMenu's constructor/factory takes
whatever it needs (GuiConfig, RealmActions, RealmCache, ChatInputService, the Plugin instance,
and GuiManager itself if menus need to open OTHER menus by calling guiManager.open(...) rather
than player.openInventory(...) directly — prefer routing every open through GuiManager so
tracking stays consistent). For each key present in GuiConfig.mainMenu().items() (an item an
admin deleted from gui.yml is simply skipped — do not synthesize a default), place that item at
its configured slot with its configured material/name/lore, and wire its click handler:

  - "info"     -> close inventory, realmActions.showInfoSelf(player)
  - "create"   -> close inventory, send a short prompt (plain Component, e.g. "<yellow>Type
                  your new realm's name in chat.</yellow>"), chatInputService.prompt(player,
                  name -> realmActions.createRealm(player, name))
  - "invite"   -> close inventory, open a new ${PACKAGE}.gui.PlayerSelectorMenu built from
                  GuiConfig.listMenu() (substitute its "<list-title>" placeholder with something
                  like "Invite a Player"): one entry per online player EXCLUDING the viewer
                  themselves, each a PLAYER_HEAD-material item whose display name is that
                  player's name (use org.bukkit.inventory.meta.SkullMeta#setOwningPlayer for a
                  real player-face head, matching how a real server would want this to look —
                  fall back to a plain PLAYER_HEAD without a set owner if that API isn't
                  straightforward to use here, and note it in your report). Clicking an entry
                  closes the inventory and calls realmActions.invite(player, thatPlayer). Cap
                  the list at 45 entries (leaving room for the back button) and note in your
                  report that pagination is out of scope for this pass — an explicit, called-out
                  simplification, not a silent limitation. If there are zero other online
                  players, show a single non-clickable filler-style informational item instead
                  of an empty grid (e.g. "<gray>No other players online</gray>").
  - "join"     -> close inventory, open a new ${PACKAGE}.gui.RealmSelectorMenu, same list-menu
                  chrome (title "Join a Realm"): one entry per realm in realmCache.values()
                  EXCLUDING the realm the viewer already belongs to (if any) — a WRITABLE_BOOK
                  item whose name is the realm's display name and whose lore shows at least the
                  leader's name (resolve via Bukkit.getOfflinePlayer(realm.leaderUuid()).getName(),
                  same null-fallback pattern RealmActions.showInfo already uses) and the realm's
                  level. Clicking an entry closes the inventory and calls realmActions.join(player,
                  thatRealm). Same 45-entry cap and empty-state handling as the invite menu.
  - "leave"    -> close inventory, open a ConfirmMenu whose onConfirm calls
                  realmActions.leave(player) and onCancel just closes the inventory (or reopens
                  MainMenu — your choice, document it)
  - "disband"  -> same shape as leave, onConfirm calls realmActions.disband(player)
  - "balance"  -> close inventory, realmActions.showBalance(player)
  - "deposit"  -> close inventory, open an AmountMenu whose onAmountChosen calls
                  realmActions.deposit(player, amount) and onCancel closes the inventory
  - "withdraw" -> same shape as deposit, onAmountChosen calls realmActions.withdraw(player, amount)
  - "claim"    -> close inventory, realmActions.previewClaim(player) (this already sends the
                  price/particle preview via chat+particles exactly like the command does),
                  THEN immediately also open a ConfirmMenu whose onConfirm calls
                  realmActions.confirmClaim(player) — the confirm button is simply an
                  alternative to typing "/realm claim confirm", it does not need the price
                  threaded through the GUI layer since the chat message already showed it
  - "unclaim"  -> close inventory, realmActions.unclaim(player) — no confirmation step, matching
                  the existing command's behavior exactly (parity, not a new design decision)
  - "map"      -> close inventory, realmActions.showMap(player)
  - "borders"  -> close inventory, realmActions.toggleBorders(player)
  - "close"    -> just close the inventory, no action

MainMenu needs a public "open(Player player)" (or similar) entry point that a later
plugin-wiring stage and a new /realm menu command will call.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, MainMenu's
exact public API (the method a command will call to open it, and its constructor parameters),
and confirm every one of the 13 actions above is wired (list any you could not wire and why).
`, { label: 'action-screens', phase: 'Action Screens' })

// ---------------------------------------------------------------------------
// Stage 4 — Wiring: FlameRealmsPlugin constructs everything, /realm menu command
// ---------------------------------------------------------------------------
phase('Wiring')
const wiring = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
RealmActions, the generic GUI framework, and MainMenu (with its selector/confirm/amount menus)
all now exist. Reports:

DB resilience: """${typeof resilience === 'string' ? resilience : JSON.stringify(resilience)}"""
Actions refactor: """${typeof actionsRefactor === 'string' ? actionsRefactor : JSON.stringify(actionsRefactor)}"""
Action screens: """${typeof actionScreens === 'string' ? actionScreens : JSON.stringify(actionScreens)}"""

Read src/main/java/com/flamerealms/FlameRealmsPlugin.java and
src/main/java/com/flamerealms/command/realm/RealmCommand.java IN FULL first (both have changed
since the last time either was read by an earlier stage).

1. In FlameRealmsPlugin.onEnable(), after RealmActions/RealmCommand are constructed:
   - Load ${PACKAGE}.gui.GuiConfig via its load(this) factory (or whatever the GUI-framework
     report's exact signature is).
   - Construct ${PACKAGE}.gui.ChatInputService and register it as a Listener
     (getServer().getPluginManager().registerEvents(...)).
   - Construct ${PACKAGE}.gui.GuiManager and register it as a Listener the same way.
   - Construct ${PACKAGE}.gui.MainMenu with whatever it needs (guiConfig, the RealmActions
     instance, realmCache, the chatInputService, guiManager, this plugin instance — match its
     actual constructor from the action-screens report).
   - Pass the MainMenu (or a way to open it) into RealmCommand's constructor.

2. Add a new "/realm menu" subcommand to RealmCommand (its own buildMenu()/executeMenu()
   method, following the EXACT same shape as buildMap()/executeMap() — synchronous,
   requirePlayer, gated by a new "flamerealms.command.menu" permission via
   hasPermission(...)): calls mainMenu.open(player) (or guiManager.open(player, mainMenu) —
   whichever MainMenu's actual API from the previous stage expects).

3. Add the "flamerealms.command.menu" permission node to plugin.yml's permissions: block,
   default true, plus add it to the flamerealms.command.* umbrella's children list — copy the
   exact style of the existing "map"/"borders" entries there.

4. Update messages.yml's "usage" key to include "menu" in the command list (matching its
   existing "<create|info|...|borders>" style).

5. Double-check (read the file, don't assume) that RealmCommand's describeError() (or
   RealmActions.describeError() if the previous stage moved it there — confirm which) actually
   has the DatabaseUnavailableException case from the DB-resilience stage's report; if it's
   missing, add it now (error-database-unavailable, exact key from that report).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files edited, and confirm
FlameRealmsPlugin.onEnable() constructs every new object in a sensible order (config/services
before anything that depends on them) with no null-dependency ordering bugs.
`, { label: 'wiring', phase: 'Wiring' })

// ---------------------------------------------------------------------------
// Stage 5 — Tests
// ---------------------------------------------------------------------------
phase('Tests')
const tests = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
Everything from this workflow (DB resilience, expanded config, RealmActions, the GUI
framework, MainMenu and its sub-menus, and plugin wiring) now exists. Reports:

DB resilience: """${typeof resilience === 'string' ? resilience : JSON.stringify(resilience)}"""
GUI framework: """${typeof guiFramework === 'string' ? guiFramework : JSON.stringify(guiFramework)}"""
Actions refactor: """${typeof actionsRefactor === 'string' ? actionsRefactor : JSON.stringify(actionsRefactor)}"""
Action screens: """${typeof actionScreens === 'string' ? actionScreens : JSON.stringify(actionScreens)}"""
Wiring: """${typeof wiring === 'string' ? wiring : JSON.stringify(wiring)}"""

Add tests, following this project's existing style (read
src/test/java/com/flamerealms/service/TreasuryServiceImplTest.java and one Fake*Dao class
first to match conventions — hand-written fakes, no Mockito):

1. A focused test for the core resilience feature: AsyncDatabaseExecutor.unavailable().submit(...)
   completes its returned future exceptionally with DatabaseUnavailableException, synchronously
   enough to assert on directly (CompletableFuture#isCompletedExceptionally() /
   #exceptionally(...)), WITHOUT touching any real DataSource/thread pool (this is the whole
   point of the "unavailable" factory — verify it never NPEs on a null dataSource/executor).
   Also test that shutdown(...) on an unavailable-mode instance is a safe no-op (doesn't throw).

2. RealmActions parity tests using the EXISTING Fake*Dao classes and
   InlineAsyncDatabaseExecutors (do not write new fakes unless something genuinely new is
   needed, e.g. if RealmActions now needs a dependency none of the existing
   RealmServiceImplTest/TreasuryServiceImplTest setup already provides — reuse what's there):
   pick at least 3 representative RealmActions methods (e.g. createRealm, deposit or withdraw,
   and previewClaim+confirmClaim together) and verify they produce the same underlying
   service-layer effects RealmServiceImplTest/TreasuryServiceImplTest/ClaimServiceImplTest
   already verify for the equivalent RealmService/TreasuryService/ClaimService calls — the
   point is confirming RealmActions is a thin, correct pass-through, not re-testing business
   logic those existing test classes already cover in depth.

3. If GuiConfig's parsing/validation logic (Material.valueOf fallback, slot/size bounds
   checking) can be tested with a plain in-memory YamlConfiguration instance the same way this
   project's other config classes could be (check whether DatabaseConfig/VisualizationConfig/
   PricingConfig currently have any tests at all — if none of them do, that's a signal this
   project has not established a pattern for testing config-loading classes yet, and it is fine
   to skip this for GuiConfig too rather than inventing a new testing pattern unilaterally;
   note in your report either way which you concluded and why).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
explicitly confirm whether you were able to run the fake-DAO/unit tests in this environment and
whether they passed.
`, { label: 'gui-tests', phase: 'Tests' })

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
Final verification pass on the FlameRealms plugin project (package ${PACKAGE}) in the current
directory, covering everything built across this workflow (DB resilience, expanded config, the
RealmActions refactor, the full GUI layer, plugin wiring, and tests) on top of the
already-existing M0-M3 code:

DB resilience: """${typeof resilience === 'string' ? resilience : JSON.stringify(resilience)}"""
GUI framework: """${typeof guiFramework === 'string' ? guiFramework : JSON.stringify(guiFramework)}"""
Actions refactor: """${typeof actionsRefactor === 'string' ? actionsRefactor : JSON.stringify(actionsRefactor)}"""
Action screens: """${typeof actionScreens === 'string' ? actionScreens : JSON.stringify(actionScreens)}"""
Wiring: """${typeof wiring === 'string' ? wiring : JSON.stringify(wiring)}"""
Tests: """${typeof tests === 'string' ? tests : JSON.stringify(tests)}"""

Do the following, in order:
1. Run the build (./gradlew build if the wrapper exists, otherwise gradle build). Read the full
   error output if it fails.
2. If there are compile errors, fix them directly — you have full Read/Edit/Bash access to the
   whole project. This workflow had FIVE agents touching an unusually large, interconnected set
   of new files across two parallel stages and three sequential ones — check these seams first:
   - RealmCommand.java actually compiling against RealmActions' real method signatures (not a
     paraphrase from an earlier report)
   - MainMenu/PlayerSelectorMenu/RealmSelectorMenu/ConfirmMenu/AmountMenu all calling
     GuiConfig's real accessor names and RealmActions' real method names
   - FlameRealmsPlugin.java constructing every new object (GuiConfig, ChatInputService,
     GuiManager, MainMenu, RealmActions) in an order where nothing is used before it's built,
     and passing the right types into RealmCommand's (possibly changed) constructor
   - AsyncDatabaseExecutor.unavailable() and DatabaseUnavailableException actually being used
     consistently between the DB-resilience stage's code and RealmActions'/RealmCommand's
     describeError() case
   - MoneyParsing/InvalidAmountException actually replacing RealmCommand's old private copies
     everywhere, with no leftover duplicate definitions causing ambiguity
   - plugin.yml's new "flamerealms.command.menu" permission actually present and wired into
     RealmCommand's requires(...) the same way every other subcommand's permission is
3. Once it compiles, run the unit test task (skip/exclude the Docker-dependent integration
   tests explicitly if Docker isn't available in this sandbox — note whether it was available).
4. As a light manual sanity check (no live Bukkit server needed for this part), grep the final
   RealmCommand.java + MainMenu.java to confirm all 13 actions (info, create, invite, join,
   leave, disband, balance, deposit, withdraw, claim, unclaim, map, borders) each still have a
   working command path AND a GUI path — report any that seem to have been dropped.
5. Report exactly what still doesn't work, if anything — do not claim something works if you
   didn't actually verify it.

Do not read PROJECT.md or TECHNICAL_SPEC.md unless you hit something genuinely ambiguous that
the reports above don't resolve.
`, { schema: VERIFY_SCHEMA, label: 'verify', phase: 'Verify' })

let fixAttempts = 0
while (!verify.buildSucceeded && fixAttempts < 3) {
  fixAttempts++
  log(`Build still failing, fix attempt ${fixAttempts}/3...`)
  verify = await agent(`
The FlameRealms build is still failing after a previous attempt. Its report:
"""${JSON.stringify(verify)}"""

Fix the remaining compile/build errors directly (Read/Edit/Bash access to the whole project),
then re-run the build and the unit test task, and report the same structured result again
(buildSucceeded, unitTestsSucceeded, summary, remainingIssues).
`, { schema: VERIFY_SCHEMA, label: `verify-fix-${fixAttempts}`, phase: 'Verify' })
}

log(verify.buildSucceeded
  ? `Build succeeded after ${fixAttempts} fix attempt(s). Unit tests ${verify.unitTestsSucceeded ? 'passed' : 'did NOT pass'}.`
  : `Build still failing after ${fixAttempts} fix attempt(s) — see remainingIssues.`)

return { ...verify, fixAttempts }
