package com.lgsudoku;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * LootGames 助手 — clientside helpers for the minigames shipped by
 * <a href="https://github.com/GTNewHorizons/LootGames">LootGames</a> (GTNH):
 *
 * <ul>
 * <li><b>数独</b>: LootGames syncs the full solution, so the board is simply filled with the same
 * right clicks a player would send.</li>
 * <li><b>扫雷</b>: the solution is <em>not</em> synced (hidden fields have no type), so the board is
 * solved from the revealed numbers and safe cells are revealed with normal left clicks.</li>
 * <li><b>游戏灯</b>: LootGames syncs the shown symbol sequence, so it is recorded and repeated with
 * normal left clicks.</li>
 * </ul>
 *
 * <p>
 * All LootGames access is reflective, so this jar never hard-depends on one particular LootGames
 * build.
 */
@Mod(
        modid = LgsudokuMod.MODID,
        name = LgsudokuMod.NAME,
        version = LgsudokuMod.VERSION,
        acceptableRemoteVersions = "*",
        dependencies = "after:lootgames")
public class LgsudokuMod {

    public static final String MODID = "lgsudoku";
    public static final String NAME = "LootGames Helper";
    public static final String VERSION = "1.2.0";

    private static final Logger LOG = LogManager.getLogger(NAME);

    public static KeyBinding keySolve;
    public static KeyBinding keyHints;

    /** Sudoku: how many blank cells may be filled per client tick (each needs 1-9 right clicks). */
    public static int cellsPerTick = 3;
    /** Minesweeper: how many cells may be revealed per client tick. */
    public static int revealPerTick = 6;
    /** Render the answer / deduced cells in the world. */
    public static boolean showHints = true;
    /** Print the progress line in the HUD while solving. */
    public static boolean showHud = true;
    /** The board is searched in this radius (blocks) around the player. */
    public static double scanRadius = 24.0D;
    /**
     * Only click cells within this distance. The server hard limit is 6 blocks, but its copy of
     * the player position lags a tick behind, so anything near 6 gets dropped while walking.
     */
    public static double reachBlocks = 5.0D;

    public static void log(String message) {
        LOG.info("[lgsudoku] " + message);
    }

    public static void log(String message, Throwable throwable) {
        LOG.error("[lgsudoku] " + message, throwable);
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        try {
            File file = event.getSuggestedConfigurationFile();
            Configuration cfg = new Configuration(file);
            cfg.load();
            cellsPerTick = cfg.getInt(
                    "cellsPerTick",
                    "sudoku",
                    3,
                    1,
                    8,
                    "数独：每次客户端 tick 最多填写多少格（每格需要 1-9 次右键）。");
            revealPerTick = cfg.getInt(
                    "revealPerTick",
                    "minesweeper",
                    6,
                    1,
                    24,
                    "扫雷：每次客户端 tick 最多翻开多少格。");
            showHints = cfg.getBoolean("showHints", "client", true, "在棋盘上显示答案/推断结果。");
            showHud = cfg.getBoolean("showHud", "client", true, "在屏幕左上角显示解题进度。");
            scanRadius = cfg.getFloat("scanRadius", "client", 24.0F, 4.0F, 64.0F, "搜索棋盘的半径（格）。");
            reachBlocks = cfg.getFloat(
                    "reachBlocks",
                    "client",
                    5.0F,
                    2.0F,
                    6.0F,
                    "只点击这个距离内的格子。服务端上限是 6 格，但玩家坐标在服务端会晚 1 tick，贴边点会被丢掉，所以留余量；够不到的格子会提示你走过去。");
            if (cfg.hasChanged()) {
                cfg.save();
            }
        } catch (Throwable t) {
            log("读取配置失败，使用默认值", t);
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (!event.getSide()
                .isClient()) {
            return;
        }

        keySolve = new KeyBinding("key.lgsudoku.solve", Keyboard.KEY_K, "key.categories.lgsudoku");
        keyHints = new KeyBinding("key.lgsudoku.hints", Keyboard.KEY_L, "key.categories.lgsudoku");
        ClientRegistry.registerKeyBinding(keySolve);
        ClientRegistry.registerKeyBinding(keyHints);

        FMLCommonHandler.instance()
                .bus()
                .register(new ClientTicker());
        MinecraftForge.EVENT_BUS.register(new HintRenderer());
        MinecraftForge.EVENT_BUS.register(new HudOverlay());

        log(
                "ready (cellsPerTick=" + cellsPerTick
                        + ", revealPerTick=" + revealPerTick
                        + ", hints=" + showHints
                        + ")");
    }

    /** Drives the keybinds and both solver state machines. */
    public static final class ClientTicker {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) {
                SudokuBridge.invalidate();
                MinesweeperBridge.invalidate();
                GameOfLightBridge.invalidate();
                AutoSolver.abort();
                MinesweeperSolver.abort();
                GameOfLightSolver.abort();
                return;
            }

            if (keySolve != null && keySolve.isPressed()) {
                SolveController.toggle(mc);
            }
            if (keyHints != null && keyHints.isPressed()) {
                showHints = !showHints;
                mc.thePlayer.addChatMessage(
                        new ChatComponentText(
                                EnumChatFormatting.AQUA + "[LootGames 助手] "
                                        + EnumChatFormatting.RESET
                                        + "棋盘提示："
                                        + (showHints ? "开" : "关")));
            }

            SudokuBridge.tickCache(mc);
            MinesweeperBridge.tickCache(mc);
            GameOfLightBridge.tickCache(mc);
            AutoSolver.tick(mc);
            MinesweeperSolver.tick(mc);
            GameOfLightSolver.tick(mc);
        }
    }
}
