package com.skyblock.dungeon.spawn;

import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.loot.DungeonLootTable;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Places a physical chest with rolled loot in CHEST-type rooms the
 * moment they're carved, per "chest rooms scattered in generated
 * terrain." Intended to be called from a
 * DungeonRoomPlanner.RoomCarveListener.
 *
 * Looting state itself (room.markLooted()) is NOT set by this class -
 * that has to happen when a player actually empties the chest
 * (an InventoryCloseEvent/BlockBreakEvent listener elsewhere should
 * call room.markLooted() once the chest is emptied/destroyed), since
 * this class only runs once at carve time and has no way to observe
 * later player interaction. Once marked looted, the room stays empty
 * forever per the "permanent once explored" scarcity rule - this
 * class will never refill an already-placed chest.
 */
public final class DungeonChestRoomPlacer {

    private static final int MIN_ITEMS = 2;
    private static final int MAX_ITEMS = 5;

    private final DungeonLootTable lootTable;
    private final Random random;

    public DungeonChestRoomPlacer(DungeonLootTable lootTable, Random random) {
        this.lootTable = lootTable;
        this.random = random;
    }

    public void placeForRoom(World world, int floorNumber, int floorBottomY, DungeonRoom room) {
        if (room.type() != DungeonRoom.Type.CHEST) {
            return;
        }
        if (room.isLooted()) {
            return; // already emptied earlier this week - permanent scarcity, never refill
        }

        int chestX = room.centerX();
        int chestZ = room.centerZ();
        Block block = world.getBlockAt(chestX, floorBottomY + 1, chestZ);
        block.setType(Material.CHEST);

        if (!(block.getState() instanceof Chest chestState)) {
            return; // shouldn't happen given the setType above, but stay defensive
        }

        int itemCount = MIN_ITEMS + random.nextInt(MAX_ITEMS - MIN_ITEMS + 1);
        List<ItemStack> loot = lootTable.rollLoot(floorNumber, itemCount);
        for (ItemStack item : loot) {
            chestState.getInventory().addItem(item);
        }
        chestState.update();
    }
}
