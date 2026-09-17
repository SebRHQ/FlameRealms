package com.flamerealms.protection;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.Messages;
import com.flamerealms.domain.BlockCoordinate;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Makes every active realm's Nexus block indestructible: cancels any {@link
 * BlockBreakEvent} on a block {@link RealmCache#isNexus} reports as a Nexus,
 * unless the breaking player holds {@code flamerealms.admin} — the one,
 * existing, reserved admin-only permission node in {@code plugin.yml} (see
 * that file's own description; no second admin node is introduced here).
 *
 * <p>A disbanded realm's Nexus stops being tracked by {@link RealmCache}
 * (see {@code RealmServiceImpl#disbandRealm}'s {@code removeNexus(...)} call)
 * and so becomes an ordinary, breakable block again with no special-casing
 * needed here — this listener only ever consults the cache's current state,
 * never a realm's own record of where its Nexus is.
 *
 * <p>An ordinary Bukkit {@link Listener}, registered once from {@code
 * FlameRealmsPlugin#onEnable()} via {@code
 * getServer().getPluginManager().registerEvents(...)}, the same way {@code
 * GuiManager}/{@code ChatInputService} already are.
 */
public final class NexusProtectionListener implements Listener {

    private final RealmCache realmCache;
    private final Messages messages;

    public NexusProtectionListener(RealmCache realmCache, Messages messages) {
        this.realmCache = realmCache;
        this.messages = messages;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockCoordinate location = new BlockCoordinate(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());

        if (!realmCache.isNexus(location)) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("flamerealms.admin")) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(messages.get("error-nexus-indestructible"));
    }
}
