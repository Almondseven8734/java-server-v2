package com.skyblock.dungeon.command;

import com.skyblock.dungeon.floor.DungeonPlayerState;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Function;

/**
 * Handles /dungeon: teleports the player into Floor 0 (the entrance
 * hub) and marks them as inside the dungeon, which activates the
 * command lockdown and death/portal handlers.
 *
 * Per design there is no teleport back out and no skipping ahead -
 * this command only ever sends a player to Floor 0. Resuming at a
 * deeper floor after a relog is handled separately (on join, not via
 * this command), since /dungeon's only job is the initial entrance.
 */
public final class DungeonCommand implements CommandExecutor {

    private final Function<UUID, DungeonPlayerState> stateLookup;
    private final Location floor0Location;

    public DungeonCommand(Function<UUID, DungeonPlayerState> stateLookup, Location floor0Location) {
        this.stateLookup = stateLookup;
        this.floor0Location = floor0Location;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
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
}
