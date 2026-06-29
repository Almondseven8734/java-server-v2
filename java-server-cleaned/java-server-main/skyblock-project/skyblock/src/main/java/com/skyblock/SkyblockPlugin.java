package com.skyblock;

import com.skyblock.admin.AdminSystem;
import com.skyblock.ah.AhCommand;
import com.skyblock.ah.AhStorage;
import com.skyblock.ah.AhSystem;
import com.skyblock.commands.FlyCommand;
import com.skyblock.commands.SpawnCommand;
import com.skyblock.commands.TeleportCommands;
import com.skyblock.crates.CrateCommand;
import com.skyblock.crates.CrateSystem;
import com.skyblock.dev.TestForms;
import com.skyblock.economy.BalCommand;
import com.skyblock.economy.PayCommand;
import com.skyblock.fishing.FishingSystemV4;
import com.skyblock.island.InviteSystem;
import com.skyblock.island.IslandCommands;
import com.skyblock.island.IslandGenerator;
import com.skyblock.island.IslandMenu;
import com.skyblock.island.IslandPortal;
import com.skyblock.island.IslandProtection;
import com.skyblock.items.SpecialItems;
import com.skyblock.kills.KillSystem;
import com.skyblock.mine.MineSystem;
import com.skyblock.minigames.MinigamesSystem;
import com.skyblock.protection.SpawnMobControl;
import com.skyblock.protection.SpawnProtection;
import com.skyblock.pwarp.PwarpSystem;
import com.skyblock.shop.AdminShopSystem;
import com.skyblock.shop.GemshopSystem;
import com.skyblock.shop.KillShopSystem;
import com.skyblock.shop.SellCommand;
import com.skyblock.shop.SellTrashSystem;
import com.skyblock.shop.ShopCommand;
import com.skyblock.shop.ShopSystem;
import com.skyblock.shop.TrashCommand;
import com.skyblock.sidebar.SidebarSystem;
import com.skyblock.storage.IslandStorage;
import com.skyblock.tpa.TpaSystem;
import com.skyblock.util.GuiBuilder;
import com.skyblock.util.NameValidator;
import com.skyblock.vault.VaultSystem;
import com.skyblock.vote.VoteSystem;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;


public class SkyblockPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        GuiBuilder.register(this);
        PluginManager pm = getServer().getPluginManager();

        // ── Storage ──────────────────────────────────────────────────────────
        IslandStorage islandStorage = new IslandStorage(getDataFolder(), getLogger());

        // ── Island systems ───────────────────────────────────────────────────
        IslandGenerator  islandGenerator  = new IslandGenerator(getLogger());
        InviteSystem     inviteSystem      = new InviteSystem(getLogger());
        PwarpSystem      pwarpSystem       = new PwarpSystem(islandStorage, getLogger());
        IslandMenu       islandMenu        = new IslandMenu(this, islandStorage, islandGenerator, inviteSystem, new NameValidator());
        IslandCommands   islandCommands    = new IslandCommands(islandStorage, islandGenerator, inviteSystem, pwarpSystem, this, getLogger());
        islandCommands.setIslandMenu(islandMenu);
        IslandProtection islandProtection  = new IslandProtection(islandStorage, this, getLogger());
        IslandPortal     islandPortal      = new IslandPortal(islandStorage, this, getLogger());

        // ── Economy commands ─────────────────────────────────────────────────
        BalCommand balCommand = new BalCommand(getLogger());
        PayCommand payCommand = new PayCommand(getLogger());

        // ── Auction House ────────────────────────────────────────────────────
        AhStorage ahStorage = new AhStorage(getDataFolder(), getLogger());
        AhSystem  ahSystem  = new AhSystem(ahStorage, this, getLogger());
        AhCommand ahCommand = new AhCommand(ahSystem);

        // ── Shop / Sell / Trash ──────────────────────────────────────────────
        ShopSystem      shopSystem      = new ShopSystem(this, getLogger());
        SellTrashSystem sellTrashSystem = new SellTrashSystem(this, getLogger());

        // ── Crates ───────────────────────────────────────────────────────────
        com.skyblock.items.SpecialItems specialItems = new com.skyblock.items.SpecialItems(this);
        CrateSystem  crateSystem  = new CrateSystem(this, specialItems);
        CrateCommand crateCommand = new CrateCommand(this);

        // ── Fishing ──────────────────────────────────────────────────────────
        FishingSystemV4 fishingV4 = new FishingSystemV4(this, getLogger());

        // ── Gem Shop ─────────────────────────────────────────────────────────
        GemshopSystem gemshopSystem = new GemshopSystem(this, crateSystem);

        // ── Admin (must be before MineSystem) ────────────────────────────────
        AdminShopSystem adminShopSystem = new AdminShopSystem(this, getLogger(), crateSystem);
        AdminSystem     adminSystem     = new AdminSystem(this, islandStorage, getLogger(), adminShopSystem);

        // ── Mine ─────────────────────────────────────────────────────────────
        MineSystem mineSystem = new MineSystem(this, adminSystem, crateSystem);
        specialItems.setMineSystem(mineSystem);

        // ── Minigames ────────────────────────────────────────────────────────
        MinigamesSystem minigamesSystem = new MinigamesSystem(this, getLogger());

        // ── Spawn / Teleport ─────────────────────────────────────────────────
        SpawnCommand     spawnCommand     = new SpawnCommand(this, getLogger());
        SpawnProtection  spawnProtection  = new SpawnProtection(getLogger());
        SpawnMobControl  spawnMobControl  = new SpawnMobControl(this, getLogger());
        TeleportCommands teleportCommands = new TeleportCommands(getLogger());

        // ── Vote ─────────────────────────────────────────────────────────────
        VoteSystem voteSystem = new VoteSystem(this, getLogger(), crateSystem);

        // ── Kill Tracking / Kill Shop ─────────────────────────────────────────
        KillSystem killSystem = new KillSystem(this, crateSystem, getLogger());
        KillShopSystem killShopSystem = new KillShopSystem(this, crateSystem, getLogger());

        // ── Sidebar Scoreboard ────────────────────────────────────────────────
        SidebarSystem sidebarSystem = new SidebarSystem(this, getLogger());

        // ── TPA System ────────────────────────────────────────────────────────
        TpaSystem tpaSystem = new TpaSystem(this, getLogger());

        // ── Fly Command ───────────────────────────────────────────────────────
        FlyCommand flyCommand = new FlyCommand(islandStorage, getLogger());

        // ── Player Vaults ────────────────────────────────────────────────────
        VaultSystem vaultSystem = new VaultSystem(this, getLogger());

        // ── Dev ──────────────────────────────────────────────────────────────
        TestForms testForms = new TestForms(this, getLogger());

        // ── Register commands ─────────────────────────────────────────────────
        getCommand("admin").setExecutor(adminSystem);
        getCommand("ah").setExecutor(ahCommand);
        getCommand("bal").setExecutor(balCommand);
        getCommand("crates").setExecutor(crateCommand);
        getCommand("fish").setExecutor(fishingV4);
        getCommand("gemshop").setExecutor(gemshopSystem);
        getCommand("is").setExecutor(islandCommands);
        getCommand("mine").setExecutor(teleportCommands);
        getCommand("resetmine").setExecutor(mineSystem);
        getCommand("minigames").setExecutor(minigamesSystem);
        getCommand("dungeons").setExecutor(teleportCommands);
        getCommand("pvp").setExecutor(teleportCommands);
        getCommand("pay").setExecutor(payCommand);
        getCommand("pwarp").setExecutor(pwarpSystem);
        getCommand("setwarp").setExecutor(islandCommands);
        getCommand("delwarp").setExecutor(islandCommands);
        getCommand("sell").setExecutor(new SellCommand(sellTrashSystem));
        getCommand("sellall").setExecutor(new SellCommand(sellTrashSystem));
        getCommand("shop").setExecutor(new ShopCommand(shopSystem));
        getCommand("spawn").setExecutor(spawnCommand);
        getCommand("hub").setExecutor(spawnCommand);
        getCommand("trash").setExecutor(new TrashCommand(sellTrashSystem));
        getCommand("vote").setExecutor(voteSystem);
        getCommand("vault").setExecutor(vaultSystem);
        getCommand("testforms").setExecutor(testForms);
        getCommand("killshop").setExecutor(killShopSystem);
        getCommand("kills").setExecutor(killSystem);
        getCommand("tpa").setExecutor(tpaSystem);
        getCommand("tphere").setExecutor(tpaSystem);
        getCommand("tpaccept").setExecutor(tpaSystem);
        getCommand("tpdeny").setExecutor(tpaSystem);
        getCommand("fly").setExecutor(flyCommand);

        // ── Register listeners ────────────────────────────────────────────────
        pm.registerEvents(adminSystem, this);
        pm.registerEvents(adminShopSystem, this);
        pm.registerEvents(islandMenu, this);
        pm.registerEvents(islandProtection, this);
        pm.registerEvents(islandPortal, this);
        pm.registerEvents(inviteSystem, this);
        pm.registerEvents(spawnProtection, this);
        pm.registerEvents(spawnMobControl, this);
        pm.registerEvents(fishingV4, this);
        pm.registerEvents(crateSystem, this);
        pm.registerEvents(mineSystem, this);
        pm.registerEvents(voteSystem, this);
        pm.registerEvents(spawnCommand, this);
        pm.registerEvents(testForms, this);
        pm.registerEvents(gemshopSystem, this);
        pm.registerEvents(ahSystem, this);
        pm.registerEvents(sellTrashSystem, this);
        pm.registerEvents(shopSystem, this);
        pm.registerEvents(minigamesSystem, this);
        pm.registerEvents(vaultSystem, this);
        pm.registerEvents(killSystem, this);
        pm.registerEvents(killShopSystem, this);
        pm.registerEvents(sidebarSystem, this);
        pm.registerEvents(tpaSystem, this);
        pm.registerEvents(flyCommand, this);

        getLogger().info("[SkyblockPlugin] All systems enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("[SkyblockPlugin] Plugin disabled.");
    }
}
