package com.skyblock.dungeon.util;

import java.util.Locale;
import java.util.Set;

/**
 * Determines which commands are blocked while a player is physically
 * inside the dungeon. Per design: only movement/escape-hatch commands
 * are blocked (e.g. /tpa, /hub, /mine); everything else (chat, /msg,
 * inventory commands, etc.) keeps working normally.
 *
 * The only legitimate ways out are dying or walking back through the
 * Floor 0 portal - this filter enforces that no command can bypass it.
 */
public final class DungeonCommandLockdown {

    /**
     * Command labels (without leading slash, lowercase, no arguments)
     * that are blocked while inside the dungeon. Aliases are included
     * explicitly since Bukkit command label resolution can vary.
     */
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
        "tpa", "tphere", "tpaccept", "tpdeny",
        "hub", "spawn",
        "mine", "resetmine",
        "pvp",
        "is", // island teleport subcommands (go/spawn/etc.) - block entirely while inside
        "pwarp", "pw",
        "minigames",
        "fly" // flight isn't permitted inside the dungeon either - too easy to skip generation/exploration
    );

    private DungeonCommandLockdown() {
    }

    /**
     * @param commandLabel the command label as Bukkit reports it (without the leading slash)
     * @return true if this command should be blocked for a player currently inside the dungeon
     */
    public static boolean isBlocked(String commandLabel) {
        if (commandLabel == null) {
            return false;
        }
        return BLOCKED_COMMANDS.contains(commandLabel.toLowerCase(Locale.ROOT));
    }

    public static Set<String> blockedCommands() {
        return BLOCKED_COMMANDS;
    }
}
