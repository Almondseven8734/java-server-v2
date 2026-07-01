package com.skyblock.dungeon.listener;

import com.skyblock.admin.AdminSystem;
import com.skyblock.dungeon.floor.DungeonPlayerState;
import com.skyblock.dungeon.util.DungeonCommandLockdown;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Wires DungeonCommandLockdown into a real Bukkit event: cancels any
 * blocked command (movement/escape-hatch commands like /tpa, /hub,
 * /mine, /fly, etc.) while the player is physically inside the
 * dungeon. All other commands pass through untouched, per design.
 *
 * Admins with the COMMAND_OVERRIDE permission can bypass this entirely
 * via "/admin execute <command>" - that flow flags the acting player as
 * override-active for the duration of the dispatch, which this listener
 * checks below.
 */
public final class DungeonCommandLockdownListener implements Listener {

    private final Function<UUID, DungeonPlayerState> stateLookup;

    public DungeonCommandLockdownListener(Function<UUID, DungeonPlayerState> stateLookup) {
        this.stateLookup = stateLookup;
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (AdminSystem.isOverrideActive(player.getUniqueId())) {
            return; // admin command override in progress - bypass lockdown entirely
        }

        DungeonPlayerState state = stateLookup.apply(player.getUniqueId());

        if (state == null || !state.isInsideDungeon()) {
            return; // lockdown only applies while inside the dungeon
        }

        String label = extractLabel(event.getMessage());
        if (DungeonCommandLockdown.isBlocked(label)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You can't use that command while inside the dungeon. "
                + ChatColor.GRAY + "Walk back to the Floor 0 portal, or find another way out.");
        }
    }

    /**
     * Extracts the bare command label (no leading slash, no arguments,
     * lowercase) from a raw chat-command message like "/tpa Steve".
     */
    private String extractLabel(String rawMessage) {
        String withoutSlash = rawMessage.startsWith("/") ? rawMessage.substring(1) : rawMessage;
        int spaceIndex = withoutSlash.indexOf(' ');
        String label = spaceIndex == -1 ? withoutSlash : withoutSlash.substring(0, spaceIndex);
        return label.toLowerCase(Locale.ROOT);
    }
}
