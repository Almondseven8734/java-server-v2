package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.gen.RoomGraph;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.function.IntFunction;

/**
 * Marks a CHEST-type room's DungeonRoom as looted once a player empties
 * the chest (inventory contains no items when closed) or breaks it
 * outright. Per design, once a room is looted it stays looted forever
 * for the whole server this week - DungeonChestRoomPlacer already
 * checks room.isLooted() and refuses to refill, so this listener is
 * the other half of that contract: actually flipping the flag.
 *
 * Resolves which floor's RoomGraph to check via a supplied
 * IntFunction<RoomGraph> rather than owning floor state directly,
 * keeping this decoupled from DungeonFloorManager's internals -
 * wire it as floorNumber -> floorManager.getOrCreateRoomGraph(floorNumber).
 *
 * Floor number + room lookup both happen by world coordinate since
 * chests don't carry floor metadata themselves; the caller's
 * floorForY function maps a block's Y coordinate back to a floor
 * number using FloorBounds.
 */
public final class DungeonChestLootListener implements Listener {

    @FunctionalInterface
    public interface FloorForY {
        int floorForY(int y);
    }

    private final IntFunction<RoomGraph> roomGraphForFloor;
    private final FloorForY floorForY;

    public DungeonChestLootListener(IntFunction<RoomGraph> roomGraphForFloor, FloorForY floorForY) {
        this.roomGraphForFloor = roomGraphForFloor;
        this.floorForY = floorForY;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (inv.getHolder() == null) {
            return;
        }
        if (!(inv.getHolder() instanceof org.bukkit.block.Chest chestHolder)) {
            return;
        }
        if (!isEmpty(inv)) {
            return; // still has items - not fully looted yet
        }
        markLootedIfDungeonChest(chestHolder.getBlock());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.CHEST) {
            return;
        }
        markLootedIfDungeonChest(block);
    }

    private boolean isEmpty(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                return false;
            }
        }
        return true;
    }

    private void markLootedIfDungeonChest(Block chestBlock) {
        int floorNumber = floorForY.floorForY(chestBlock.getY());
        if (floorNumber < 1) {
            return; // not inside any dungeon floor's vertical band
        }

        RoomGraph graph = roomGraphForFloor.apply(floorNumber);
        if (graph == null) {
            return;
        }

        DungeonRoom room = graph.roomContaining(chestBlock.getX(), chestBlock.getZ());
        if (room == null || room.type() != DungeonRoom.Type.CHEST) {
            return; // not a dungeon chest room - leave normal chests alone
        }

        room.markLooted();
    }
}
