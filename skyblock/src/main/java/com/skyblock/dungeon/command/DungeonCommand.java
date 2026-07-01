package com.skyblock.dungeon.command;

import com.skyblock.admin.AdminSystem;
import com.skyblock.dungeon.floor.DungeonPlayerState;
import com.skyblock.dungeon.floor.DungeonResetScheduler;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Function;

/**
 * Handles /dungeon (aliased as /dungeons in plugin.yml): teleports the
 * player into Floor 0 (the entrance hub) and marks them as inside the
 * dungeon, which activates the command lockdown and death/portal
 * handlers.
 *
 * Per design there is no teleport back out and no skipping ahead -
 * plain /dungeon only ever sends a player to Floor 0. Resuming at a
 * deeper floor after a relog is handled separately (on join, not via
 * this command), since /dungeon's only job is the initial entrance.
 *
 * Admin subcommand:
 *   /dungeon reset - forces the same reset DungeonResetScheduler runs
 *   weekly (ejects everyone inside with items intact, wipes the
 *   dungeon world, regenerates it). Gated on AdminSystem.isWhitelisted,
 *   same as /resetmine's admin check.
 *
 * floor0Location is mutable rather than final: the dungeon world is
 * deleted and recreated as a new World object on every reset, and the
 * hub gets rebuilt from scratch at that point too, so the entrance
 * target needs to move with it. Call setFloor0Location(...) with the
 * freshly rebuilt hub's entrance right after a reset.
 */
public final class DungeonCommand implements CommandExecutor {

    private final Function<UUID, DungeonPlayerState> stateLookup;
    private Location floor0Location;
    private final DungeonResetScheduler resetScheduler;

    public DungeonCommand(Function<UUID, DungeonPlayerState> stateLookup, Location floor0Location,
                           DungeonResetScheduler resetScheduler) {
        this.stateLookup = stateLookup;
        this.floor0Location = floor0Location;
        this.resetScheduler = resetScheduler;
    }

    /** Repoints the Floor 0 entrance target at the freshly rebuilt hub after a reset. */
    public void setFloor0Location(Location floor0Location) {
        this.floor0Location = floor0Location;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            handleReset(player);
            return true;
        }

        DungeonPlayerState state = stateLookup.apply(player.getUniqueId());
        if (state == null) {
            player.sendMessage("§cCould not load your dungeon state. Try again in a moment.");
            return true;
        }

        if (state.isInsideDungeon()) {
            player.sendMessage("§eYou're already inside the dungeon. Walk to the Floor 0 portal to leave, or keep exploring.");
            return true;
        }

        state.setInsideDungeon(true);
        state.setCurrentFloor(0);
        player.teleport(floor0Location);
        player.sendMessage("§aYou've entered the dungeon. There's no teleporting out - "
            + "find your way to the Floor 0 portal, or fight your way deeper.");
        return true;
    }

    private void handleReset(Player player) {
        if (!AdminSystem.isWhitelisted(player.getName())) {
            player.sendMessage("§cYou do not have permission to reset the dungeon.");
            return;
        }
        player.sendMessage("§6Forcing an immediate dungeon reset...");
        resetScheduler.performReset();
    }
}
