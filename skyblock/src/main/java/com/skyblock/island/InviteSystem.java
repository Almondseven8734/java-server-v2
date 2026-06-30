package com.skyblock.island;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Island Invite System
 *
 * Translated from island_invites.js.
 *
 * Invites persist in memory until:
 *   - Denied by the recipient
 *   - The inviter logs off  (cleared via PlayerQuitEvent)
 *   - The recipient accepts and joins
 *   - The recipient leaves their island and accepts
 *
 * Identical semantics to the JS Map<inviteeId, { ... }> implementation.
 */
public class InviteSystem implements Listener {

    // ─── Invite entry ─────────────────────────────────────────────────────────

    public static class InviteEntry {
        public final String islandId;
        public final String inviterId;
        public final String inviterName;
        public final String inviteeName;

        public InviteEntry(String islandId, String inviterId,
                           String inviterName, String inviteeName) {
            this.islandId    = islandId;
            this.inviterId   = inviterId;
            this.inviterName = inviterName;
            this.inviteeName = inviteeName;
        }
    }

    // ─── State ────────────────────────────────────────────────────────────────

    /** Map<inviteeId, InviteEntry> */
    private final Map<String, InviteEntry> pendingInvites = new HashMap<>();
    private final Logger logger;

    public InviteSystem(Logger logger) {
        this.logger = logger;
        logger.info("[Skyblock] Invite system loaded!");
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    public synchronized void createInvite(String islandId, String inviterId,
                                          String inviterName, String inviteeId,
                                          String inviteeName) {
        pendingInvites.put(inviteeId,
                new InviteEntry(islandId, inviterId, inviterName, inviteeName));
    }

    public synchronized InviteEntry getInvite(String inviteeId) {
        return pendingInvites.get(inviteeId);
    }

    public synchronized void clearInvite(String inviteeId) {
        pendingInvites.remove(inviteeId);
    }

    /**
     * Clears all invites where the given player is either the invitee
     * or the inviter (e.g. when they disconnect).
     *
     * Mirrors clearInvitesByPlayer() in island_invites.js.
     */
    public synchronized void clearInvitesByPlayer(String playerId) {
        // Clear as invitee
        pendingInvites.remove(playerId);
        // Clear as inviter
        Iterator<Map.Entry<String, InviteEntry>> it = pendingInvites.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().inviterId.equals(playerId)) it.remove();
        }
    }

    // ─── Bukkit event ─────────────────────────────────────────────────────────

    /**
     * Mirrors:
     *   world.afterEvents.playerLeave.subscribe((event) => {
     *     clearInvitesByPlayer(event.playerId);
     *   });
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearInvitesByPlayer(event.getPlayer().getUniqueId().toString());
    }
}
