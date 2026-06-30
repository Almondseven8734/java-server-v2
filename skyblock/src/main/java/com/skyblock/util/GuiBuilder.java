package com.skyblock.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * GuiBuilder — fluent Bukkit inventory GUI utility.
 *
 * Centralises every pattern that was duplicated across AhSystem, SellTrashSystem,
 * ShopSystem, GemshopSystem, IslandMenu, etc.:
 *
 *   - makeItem / glass / namedItem helpers
 *   - borderSlots / usableSlots calculation
 *   - fill / fillEmpty
 *   - Session tracking (openMenus map) + InventoryClickEvent routing
 *   - friendlyName conversion
 *   - Fluent builder API that mirrors what ChestFormData felt like in Bedrock
 *
 * ─── Usage ───────────────────────────────────────────────────────────────────
 *
 *   // 1. Register the listener once in your plugin main:
 *   GuiBuilder.register(this);
 *
 *   // 2. Build and open a GUI:
 *   GuiBuilder.large("§6§lSHOP")
 *       .fill(Material.BLACK_STAINED_GLASS_PANE)
 *       .button(4,  Material.EMERALD,  "§aBuy",    List.of("§7Click to buy"), slot -> buyItem(player))
 *       .button(49, Material.BARRIER,  "§cClose",  List.of("§7Exit"),         slot -> player.closeInventory())
 *       .open(player);
 *
 *   // 3. Open with a typed session tag (used for your own click routing):
 *   GuiBuilder.large("§6§lSELL MENU")
 *       .fill(Material.BLACK_STAINED_GLASS_PANE)
 *       .button(4, Material.EMERALD, "§aSell All", List.of("§7Sell everything"), slot -> sellAll(player))
 *       .open(player, "sell_categories");  // tag stored in session, readable in your own click handler
 *
 * ─── Session API ─────────────────────────────────────────────────────────────
 *
 *   String tag = GuiBuilder.getTag(player.getUniqueId());   // the tag from open()
 *   Object data = GuiBuilder.getData(player.getUniqueId()); // optional arbitrary state
 *   GuiBuilder.close(player.getUniqueId());                 // clear session manually
 *
 * ─── Migration guide ─────────────────────────────────────────────────────────
 *
 *   OLD (e.g. in SellTrashSystem):
 *     ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", emptyList());
 *     for (int i = 0; i < 54; i++) inv.setItem(i, filler);
 *     inv.setItem(4, makeItem(Material.EMERALD, "§aSell All", lore));
 *     openMenus.put(uuid, new MenuSession(SELL_CATEGORIES, ...));
 *     player.openInventory(inv);
 *
 *   NEW:
 *     GuiBuilder.large("§6§lSELL MENU")
 *         .fill(Material.BLACK_STAINED_GLASS_PANE)
 *         .button(4, Material.EMERALD, "§aSell All", lore, slot -> sellAll(player))
 *         .open(player, "sell_categories");
 *
 * ─── Static helpers (still available for cases where full builder is overkill) ─
 *
 *   ItemStack item = GuiBuilder.item(Material.DIAMOND, "§bDiamond", List.of("§7Worth a lot"));
 *   int[] slots    = GuiBuilder.borderSlots(54);
 *   List<Integer>  = GuiBuilder.usableSlots(54);
 *   String name    = GuiBuilder.friendlyName(Material.OAK_LOG);  // → "Oak Log"
 */
public final class GuiBuilder {

    // =========================================================================
    // STATIC LISTENER & SESSION REGISTRY
    // =========================================================================

    /** Per-player GUI sessions. */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static JavaPlugin plugin;

    /**
     * Call once in your plugin's onEnable():
     *   GuiBuilder.register(this);
     */
    public static void register(JavaPlugin pl) {
        plugin = pl;
        pl.getServer().getPluginManager().registerEvents(new GuiListener(), pl);
    }

    // ── Session ───────────────────────────────────────────────────────────────

    /** Holds the per-slot click handlers and optional tag/data for one open GUI. */
    private static final class Session {
        final Map<Integer, Consumer<Integer>> handlers;
        final String tag;   // caller-supplied string tag (e.g. "sell_categories")
        final Object data;  // caller-supplied arbitrary data

        Session(Map<Integer, Consumer<Integer>> handlers, String tag, Object data) {
            this.handlers = handlers;
            this.tag  = tag;
            this.data = data;
        }
    }

    // ── Public session accessors ───────────────────────────────────────────────

    /** Returns the tag string passed to open(), or null if no session open. */
    public static String getTag(UUID uuid) {
        Session s = SESSIONS.get(uuid);
        return s == null ? null : s.tag;
    }

    /** Returns the data object passed to open(), or null. */
    public static Object getData(UUID uuid) {
        Session s = SESSIONS.get(uuid);
        return s == null ? null : s.data;
    }

    /** Manually clears a player's session (called automatically on inventory close). */
    public static void close(UUID uuid) {
        SESSIONS.remove(uuid);
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    private static final class GuiListener implements Listener {

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player)) return;
            UUID uuid = event.getWhoClicked().getUniqueId();
            Session session = SESSIONS.get(uuid);
            if (session == null) return;

            event.setCancelled(true);

            int slot = event.getRawSlot();
            // Ignore clicks outside the top inventory (player's own inv rows)
            if (slot < 0 || slot >= event.getInventory().getSize()) return;

            Consumer<Integer> handler = session.handlers.get(slot);
            if (handler != null) {
                // Schedule on next tick to avoid "cannot open inventory during this event" errors.
                // Capture the session at click time — by the time the task runs a new menu
                // may have replaced the session, but the handler closure is already bound.
                new BukkitRunnable() {
                    @Override public void run() { handler.accept(slot); }
                }.runTask(plugin);
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            if (!(event.getPlayer() instanceof Player player)) return;
            UUID uuid = player.getUniqueId();

            // Delay by one tick: when a button handler calls player.openInventory()
            // Bukkit fires InventoryCloseEvent for the OLD inventory synchronously,
            // before the new inventory is registered.  If we remove the session here
            // immediately the brand-new menu's session is wiped out and its clicks
            // are no longer cancelled — items leak, buttons stop working.
            //
            // After one tick the player is either:
            //   a) In a new GuiBuilder menu  → SESSIONS already has the new session,
            //      so we skip removal (inventory title still matches a known GUI).
            //   b) Truly closed (Escape)     → getOpenInventory() returns the player's
            //      own inventory, title is empty/non-GUI, so we clear the session.
            new BukkitRunnable() {
                @Override public void run() {
                    if (!SESSIONS.containsKey(uuid)) return; // already cleared elsewhere
                    // If the player now has their own (player) inventory open, they
                    // escaped — clear the session so clicks are unblocked.
                    // InventoryType.CRAFTING is the player's default open view.
                    org.bukkit.inventory.InventoryView view = player.getOpenInventory();
                    if (view.getTopInventory().getType()
                            == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                        SESSIONS.remove(uuid);
                    }
                    // Otherwise the player opened a new GUI — leave the session alone.
                }
            }.runTask(plugin);
        }
    }

    // =========================================================================
    // BUILDER
    // =========================================================================

    private final Inventory inv;
    private final Map<Integer, Consumer<Integer>> handlers = new LinkedHashMap<>();

    private GuiBuilder(int rows, String title) {
        this.inv = Bukkit.createInventory(null, rows * 9, title);
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /** 6-row chest (54 slots) — matches ChestFormData('large'). */
    public static GuiBuilder large(String title) { return new GuiBuilder(6, title); }

    /** 3-row chest (27 slots) — matches ChestFormData('small'). */
    public static GuiBuilder small(String title) { return new GuiBuilder(3, title); }

    /** 1-row chest (9 slots) — useful for simple yes/no confirmations. */
    public static GuiBuilder row(String title)   { return new GuiBuilder(1, title); }

    /** Arbitrary row count (1–6). */
    public static GuiBuilder rows(int rows, String title) { return new GuiBuilder(rows, title); }

    // ── Fill helpers ──────────────────────────────────────────────────────────

    /**
     * Fills ALL slots with a blank glass pane of the given material.
     * Mirrors the common: for (int i = 0; i < 54; i++) inv.setItem(i, filler);
     */
    public GuiBuilder fill(Material mat) {
        ItemStack filler = item(mat, "§8");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        return this;
    }

    /**
     * Fills only the border slots (top row, bottom row, left/right columns).
     * Leaves inner slots untouched.
     */
    public GuiBuilder border(Material mat) {
        ItemStack filler = item(mat, "§8");
        for (int s : borderSlots(inv.getSize())) inv.setItem(s, filler);
        return this;
    }

    /**
     * Fills only slots that are currently null/AIR (after buttons are set).
     * Useful for filling leftover item slots in paginated lists.
     */
    public GuiBuilder fillEmpty(Material mat) {
        ItemStack filler = item(mat, "§8");
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack existing = inv.getItem(i);
            if (existing == null || existing.getType() == Material.AIR) {
                inv.setItem(i, filler);
            }
        }
        return this;
    }

    // ── Button / item placement ───────────────────────────────────────────────

    /**
     * Places an item at the given slot with no click handler (decoration only).
     */
    public GuiBuilder set(int slot, ItemStack stack) {
        inv.setItem(slot, stack);
        return this;
    }

    /**
     * Places an item at the given slot with no click handler (decoration only).
     */
    public GuiBuilder set(int slot, Material mat, String name, List<String> lore) {
        inv.setItem(slot, item(mat, name, lore));
        return this;
    }

    /**
     * Places a button at the given slot with a click handler.
     * This is the primary API — mirrors ChestFormData.button().
     *
     * @param slot    inventory slot index (0-based)
     * @param mat     item material
     * @param name    display name (supports §colour codes)
     * @param lore    lore lines (can be empty list)
     * @param onClick called with the clicked slot index when the player clicks
     */
    public GuiBuilder button(int slot, Material mat, String name,
                             List<String> lore, Consumer<Integer> onClick) {
        inv.setItem(slot, item(mat, name, lore));
        if (onClick != null) handlers.put(slot, onClick);
        return this;
    }

    /** Convenience overload — no lore. */
    public GuiBuilder button(int slot, Material mat, String name,
                             Consumer<Integer> onClick) {
        return button(slot, mat, name, Collections.emptyList(), onClick);
    }

    /** Convenience overload — pre-built ItemStack with click handler. */
    public GuiBuilder button(int slot, ItemStack stack, Consumer<Integer> onClick) {
        inv.setItem(slot, stack);
        if (onClick != null) handlers.put(slot, onClick);
        return this;
    }

    /**
     * Places navigation arrows at the standard positions.
     * back (slot 0 or 45), prev (slot 3 or 48), next (slot 5 or 50).
     * Pass null for any handler you don't want.
     */
    public GuiBuilder navRow(Consumer<Integer> back,
                             Consumer<Integer> prev,
                             Consumer<Integer> next,
                             Consumer<Integer> close) {
        int size = inv.getSize();
        int base = size - 9; // bottom row start

        if (back  != null) button(base,     Material.COMPASS, "§7← Back",    back);
        if (prev  != null) button(base + 3, Material.ARROW,   "§e◄ Prev",    prev);
        if (next  != null) button(base + 5, Material.ARROW,   "§eNext ►",    next);
        if (close != null) button(base + 8, Material.BARRIER,  "§cClose",    close);
        return this;
    }

    /**
     * Places a "Sell All" or primary-action button at slot 4 (top centre).
     * Shows as green EMERALD when active, grey BARRIER when disabled.
     */
    public GuiBuilder primaryAction(String label, List<String> lore,
                                    boolean active, Consumer<Integer> onClick) {
        Material mat = active ? Material.EMERALD : Material.BARRIER;
        String name  = active ? "§a§l" + label : "§7" + label;
        return button(4, mat, name, lore, active ? onClick : null);
    }

    /**
     * Places a close/back button at the standard bottom-centre slot (49 for 54-slot,
     * 22 for 27-slot, 4 for 9-slot).
     */
    public GuiBuilder closeButton(Consumer<Integer> onClick) {
        int slot = switch (inv.getSize()) {
            case 54 -> 49;
            case 27 -> 22;
            case  9 ->  4;
            default -> inv.getSize() - 5;
        };
        return button(slot, Material.BARRIER, "§cClose", Collections.singletonList("§7Exit"), onClick);
    }

    // ── Direct inventory access (for complex cases) ───────────────────────────

    /** Returns the underlying Inventory for direct slot manipulation. */
    public Inventory inventory() { return inv; }

    /** Registers a click handler for a slot without placing an item. */
    public GuiBuilder handle(int slot, Consumer<Integer> onClick) {
        if (onClick != null) handlers.put(slot, onClick);
        return this;
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    /**
     * Opens the built inventory for the player and registers the session.
     * Call this last.
     */
    public void open(Player player) {
        open(player, null, null);
    }

    /**
     * Opens the inventory and stores a string tag retrievable via getTag(uuid).
     * Use this when your system needs to know which menu is open (e.g. "sell_categories").
     */
    public void open(Player player, String tag) {
        open(player, tag, null);
    }

    /**
     * Opens the inventory with a tag and arbitrary data object.
     * Retrieve with GuiBuilder.getData(uuid).
     */
    public void open(Player player, String tag, Object data) {
        SESSIONS.put(player.getUniqueId(), new Session(new HashMap<>(handlers), tag, data));
        player.openInventory(inv);
    }

    // =========================================================================
    // STATIC HELPERS — available without builder for direct use
    // =========================================================================

    /**
     * Creates a named ItemStack with lore.
     * Replaces the makeItem() / namedItem() / glass() copies in every file.
     */
    public static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack stack = new ItemStack(mat, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Overload — no lore. */
    public static ItemStack item(Material mat, String name) {
        return item(mat, name, Collections.emptyList());
    }

    /** Overload — amount variant (e.g. for showing stack counts in inventory mirrors). */
    public static ItemStack item(Material mat, int amount, String name, List<String> lore) {
        ItemStack stack = item(mat, name, lore);
        stack.setAmount(Math.max(1, Math.min(64, amount)));
        return stack;
    }

    /**
     * Returns the border slot indices for an inventory of the given size.
     * Works for any multiple-of-9 size (9, 18, 27, 36, 45, 54).
     *
     * Replaces the private borderSlots() copies in AhSystem, SellTrashSystem, etc.
     */
    public static int[] borderSlots(int size) {
        Set<Integer> set = new LinkedHashSet<>();
        int rows = size / 9;
        // Top row
        for (int i = 0; i < 9; i++) set.add(i);
        // Bottom row
        for (int i = size - 9; i < size; i++) set.add(i);
        // Left and right columns
        for (int r = 0; r < rows; r++) {
            set.add(r * 9);
            set.add(r * 9 + 8);
        }
        return set.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    /**
     * Returns the inner (non-border) usable slot indices for an inventory.
     * For a 54-slot chest: rows 1–4, columns 1–7 = 28 slots.
     * For a 27-slot chest: row 1, columns 1–7 = 7 slots.
     *
     * Replaces the private usableSlots54() in AhSystem and the manual loops elsewhere.
     */
    public static List<Integer> usableSlots(int size) {
        int rows = size / 9;
        List<Integer> list = new ArrayList<>();
        for (int r = 1; r < rows - 1; r++)
            for (int c = 1; c <= 7; c++)
                list.add(r * 9 + c);
        return list;
    }

    /**
     * Converts a Material enum name to a human-readable display name.
     * e.g. OAK_LOG → "Oak Log", BLACK_STAINED_GLASS_PANE → "Black Stained Glass Pane"
     *
     * Replaces the private friendlyName() copies in SellTrashSystem, FishCommand, etc.
     */
    public static String friendlyName(Material mat) {
        String raw = mat.name().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1).toLowerCase());
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    /**
     * Formats an integer as a comma-separated money string.
     * e.g. 1234567 → "$1,234,567"
     *
     * Replaces the String.format("%,d") calls scattered across pay/sell/shop files.
     */
    public static String formatMoney(int amount) {
        return "$" + String.format("%,d", amount);
    }

    // =========================================================================
    // PAGINATION HELPER
    // =========================================================================

    /**
     * Slices a list for a given page and places entries into usable inner slots,
     * calling itemBuilder for each entry to produce the ItemStack.
     *
     * Returns the number of items actually placed (useful to know if page is full).
     *
     * Example:
     *   GuiBuilder gui = GuiBuilder.large("§6Items");
     *   gui.fill(Material.BLACK_STAINED_GLASS_PANE);
     *   int placed = GuiBuilder.paginate(gui, items, page, (entry, slot) -> {
     *       gui.button(slot, entry.material, entry.name, entry.lore,
     *                  s -> handleClick(player, entry));
     *   });
     */
    public static <T> int paginate(GuiBuilder gui, List<T> items, int page,
                                    BiConsumer<T, Integer> itemBuilder) {
        int size = gui.inv.getSize();
        List<Integer> slots = usableSlots(size);
        int perPage = slots.size();
        int start   = page * perPage;
        int count   = 0;

        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx >= items.size()) break;
            itemBuilder.accept(items.get(idx), slots.get(i));
            count++;
        }
        return count;
    }

    /** Returns total number of pages for a list given the usable slots of the gui size. */
    public static int totalPages(int itemCount, int guiSize) {
        int perPage = usableSlots(guiSize).size();
        return Math.max(1, (int) Math.ceil((double) itemCount / perPage));
    }

    // private constructor — static factory only
    private GuiBuilder() { throw new UnsupportedOperationException(); }
}
