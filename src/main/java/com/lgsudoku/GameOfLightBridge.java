package com.lgsudoku;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Reflection-only snapshot of the LootGames "Game of Light" board.
 *
 * <p>
 * The board is a 3x3 pattern: the eight outer cells are symbols, the centre cell starts a run.
 * LootGames syncs the {@code StageShowSequence} stage including the whole symbol sequence, so the
 * client knows what to repeat. During the answer stage the client copy of the sequence is empty,
 * which is why {@link GameOfLightSolver} remembers the sequence from the show stage.
 */
public final class GameOfLightBridge {

    private static final int CACHE_TICKS = 5;

    private static View cache;
    private static World cacheWorld;
    private static int cacheAge = CACHE_TICKS;
    private static boolean reportedFailure;

    private GameOfLightBridge() {}

    public static boolean available() {
        return Reflect.findClass(SudokuBridge.GOL_TILE_CLASS) != null;
    }

    public static void invalidate() {
        cache = null;
        cacheWorld = null;
        cacheAge = CACHE_TICKS;
    }

    public static void tickCache(Minecraft mc) {
        if (cacheAge++ >= CACHE_TICKS) {
            refresh(mc);
        }
    }

    public static View refresh(Minecraft mc) {
        cacheAge = 0;
        cacheWorld = mc == null ? null : mc.theWorld;
        cache = read(mc);
        return cache;
    }

    public static View current(Minecraft mc) {
        if (cacheWorld != mc.theWorld) {
            return refresh(mc);
        }
        return cache;
    }

    private static View read(Minecraft mc) {
        TileEntity tile = SudokuBridge.nearestTile(mc, SudokuBridge.GOL_TILE_CLASS);
        if (tile == null) {
            return null;
        }

        try {
            Object game = Reflect.call(tile, "getGame");
            if (game == null) {
                return null;
            }

            Object stage = Reflect.call(game, "getStage");
            if (stage == null) {
                return null;
            }

            String stageName = stage.getClass()
                    .getSimpleName();

            int[] sequence = null;
            int symbolIndex = 0;

            if ("StageShowSequence".equals(stageName)) {
                symbolIndex = Reflect.toInt(Reflect.read(stage, "symbolIndex"));
                Object list = Reflect.read(stage, "sequence");
                if (list instanceof List) {
                    List<?> symbols = (List<?>) list;
                    sequence = new int[symbols.size()];
                    for (int i = 0; i < symbols.size(); i++) {
                        Object symbol = symbols.get(i);
                        Object pos = Reflect.call(symbol, "getPos");
                        int x = Reflect.toInt(Reflect.call(pos, "getX"));
                        int z = Reflect.toInt(Reflect.call(pos, "getY"));
                        if (x < 0 || x > 2 || z < 0 || z > 2) {
                            sequence = null;
                            break;
                        }
                        sequence[i] = x * 3 + z;
                    }
                }
            }

            int[] origin = SudokuBridge.boardOrigin(game);
            if (origin == null) {
                return null;
            }

            reportedFailure = false;
            return new View(tile, origin[0], origin[1], origin[2], stageName, sequence, symbolIndex);
        } catch (Throwable t) {
            if (!reportedFailure) {
                reportedFailure = true;
                LgsudokuMod.log("无法读取游戏灯棋盘（LootGames 结构可能已变化）", t);
            }
            return null;
        }
    }

    /** Immutable snapshot of the synced Game of Light state. */
    public static final class View {

        public final TileEntity tile;
        /** Board origin (3x3), block coordinates. */
        public final int x;
        public final int y;
        public final int z;
        /** Simple name of the current stage, e.g. {@code StageWaitingForSequence}. */
        public final String stage;
        /** Symbols of the shown sequence as {@code x * 3 + z} offsets, or null when unknown. */
        public final int[] sequence;
        /** Index of the symbol currently being displayed (show stage only). */
        public final int symbolIndex;

        View(TileEntity tile, int x, int y, int z, String stage, int[] sequence, int symbolIndex) {
            this.tile = tile;
            this.x = x;
            this.y = y;
            this.z = z;
            this.stage = stage;
            this.sequence = sequence;
            this.symbolIndex = symbolIndex;
        }

        public int cellX(int offset) {
            return x + offset / 3;
        }

        public int cellZ(int offset) {
            return z + offset % 3;
        }

        public int centerX() {
            return x + 1;
        }

        public int centerZ() {
            return z + 1;
        }
    }
}
