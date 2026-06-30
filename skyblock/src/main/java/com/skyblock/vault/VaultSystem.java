package com.skyblock.vault;
import com.skyblock.vault.VaultStorage;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Player Vault System
 *
 * Each player gets {@value VaultStorage#MAX_VAULTS} private, persistent
 * storage containers, functionally identical to an ender chest — just split
 * across multiple independently-saved "pages" instead of one shared chest.
 * Contents survive server restarts and are completely private to the owner.
 *
 * Commands (all aliases of the same executor — see plugin.yml):
 *   /vault [n]
 *   /pv [n]
 *   /playervault [n]
 *
 * If no vault number is given it defaults to vault 0. Valid vault numbers
 * are 0 through {@code VaultStorage.MAX_VAULTS - 1} (currently 0-1).
 */
public class VaultSystem implements CommandExecutor, Listener {

    private final JavaPlugin   plugin;
    private final Logger       logger;
    private final VaultStorage storage;

    /** Tracks which vault index a player currently has open, so close knows where to save back to. */
    private final Map<UUID, Integer> openVaults = new HashMap<>();

    public VaultSystem(JavaPlugin plugin, Logger logger) {
        this.plugin  = plugin;
        this.logger  = logger;
        this.storage = new VaultStorage(plugin.getDataFolder(), logger);
        logger.info("[Vault System] Loaded.");
    }

    // =========================================================================
    // COMMAND
    // =========================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        int vaultIndex = 0;
        if (args.length > 0) {
            try {
                vaultIndex = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                player.sendMessage("§cUsage: /" + label + " [vault number]");
                return true;
            }
        }

        if (vaultIndex < 0 || vaultIndex >= VaultStorage.MAX_VAULTS) {
            player.sendMessage("§cYou only have §e" + VaultStorage.MAX_VAULTS
                    + " §cvaults available (§e0§c-§e" + (VaultStorage.MAX_VAULTS - 1) + "§c).");
            return true;
        }

        openVault(player, vaultIndex);
        return true;
    }

    // =========================================================================
    // OPEN / SAVE
    // =========================================================================

    public void openVault(Player player, int vaultIndex) {
        ItemStack[] contents = loadVaultContents(player.getUniqueId(), vaultIndex);

        Inventory inv = Bukkit.createInventory(null, VaultStorage.VAULT_SIZE,
                "§5§lVault " + vaultIndex + " §8| §7Private Storage");
        inv.setContents(contents);

        openVaults.put(player.getUniqueId(), vaultIndex);
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Integer vaultIndex = openVaults.remove(player.getUniqueId());
        if (vaultIndex == null) return;
        saveVaultContents(player.getUniqueId(), vaultIndex, event.getInventory().getContents());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Integer vaultIndex = openVaults.remove(player.getUniqueId());
        if (vaultIndex == null) return;
        // Safety net: InventoryCloseEvent normally fires on disconnect too, but
        // just in case it doesn't for some edge case, save whatever's showing.
        try {
            saveVaultContents(player.getUniqueId(), vaultIndex,
                    player.getOpenInventory().getTopInventory().getContents());
        } catch (Exception ignored) {
            // Player object may already be in a partially-torn-down state; nothing more we can do.
        }
    }

    // =========================================================================
    // SERIALIZATION — Bukkit's own object streams, so item meta/NBT/PDC tags
    // (crate keys, talismans, etc.) round-trip exactly, same as everything
    // else this plugin persists.
    // =========================================================================

    private ItemStack[] loadVaultContents(UUID playerId, int vaultIndex) {
        String raw = storage.getRaw(playerId, vaultIndex);
        if (raw == null || raw.isEmpty()) return new ItemStack[VaultStorage.VAULT_SIZE];
        try {
            return deserialize(raw);
        } catch (Exception e) {
            logger.severe("[Vault System] Failed to load vault " + vaultIndex
                    + " for " + playerId + ": " + e.getMessage());
            return new ItemStack[VaultStorage.VAULT_SIZE];
        }
    }

    private void saveVaultContents(UUID playerId, int vaultIndex, ItemStack[] contents) {
        try {
            storage.setRaw(playerId, vaultIndex, serialize(contents));
        } catch (Exception e) {
            logger.severe("[Vault System] Failed to save vault " + vaultIndex
                    + " for " + playerId + ": " + e.getMessage());
        }
    }

    private static String serialize(ItemStack[] items) throws IOException {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(byteOut)) {
            out.writeInt(items.length);
            for (ItemStack item : items) out.writeObject(item);
        }
        return Base64.getEncoder().encodeToString(byteOut.toByteArray());
    }

    private static ItemStack[] deserialize(String base64) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteIn = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(byteIn)) {
            int size = in.readInt();
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) items[i] = (ItemStack) in.readObject();
            return items;
        }
    }
}
