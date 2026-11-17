package com.lgsudoku;

import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Reflection-only access to the LootGames boards, plus the two vanilla packets a player would
 * send while interacting with them.
 *
 * <p>
 * Everything the solvers need is already replicated to the client by LootGames. All classes are
 * accessed by name on purpose: GTNH ships its own LootGames forks and no compile-time dependency
 * is wanted.
 */
public final class SudokuBridge {

    static final String SUDOKU_TILE_CLASS = "ru.timeconqueror.lootgames.common.block.tile.SudokuTile";
    static final String MS_TILE_CLASS = "ru.timeconqueror.lootgames.common.block.tile.MSMasterTile";
    static final String GOL_TILE_CLASS = "ru.timeconqueror.lootgames.common.block.tile.GOLMasterTile";

    /**
     * The server accepts a right click when
     * {@code player.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) < (blockReachDistance + 1)^2}, and
     * {@code ItemInWorldManager.blockReachDistance} is 5.0, i.e. 36.0. That is only the hard
     * limit though: the server's copy of the player position lags one tick behind while walking,
     * so clicking a cell at 5.9 blocks gets the packet silently dropped. Use a comfortable
     * margin and let the player walk (see {@code reachBlocks} in the config).
     */
    /** A left click is checked with the player's position shifted up by 1.5 blocks. */
    private static final double DIG_EYE_OFFSET = 1.5D;

    private static final int CACHE_TICKS = 5;

    private static boolean reportedFailure;

    private static BoardView sudokuCache;
    private static World sudokuCacheWorld;
    private static int sudokuCacheAge = CACHE_TICKS;

    private SudokuBridge() {}

    // ---------------------------------------------------------------- tiles

    /** @return te nearest LootGames tile within {@code LgsudokuMod.scanRadius}, or null. */
    static TileEntity nearestTile(Minecraft mc, String className) {
        World world = mc.theWorld;
        EntityPlayer player = mc.thePlayer;
        if (world == null || player == null) {
            return null;
        }
        Class<?> type = Reflect.findClass(className);
        if (type == null) {
            return null;
        }

        double limit = LgsudokuMod.scanRadius;
        limit *= limit;

        TileEntity best = null;
        double bestDistance = limit;

        for (Object entry : (List<?>) world.loadedTileEntityList) {
            if (!(entry instanceof TileEntity)) {
                continue;
            }
            TileEntity tile = (TileEntity) entry;
            if (!type.isInstance(tile)) {
                continue;
            }
            double distance = player.getDistanceSq(tile.xCoord + 0.5D, tile.yCoord + 0.5D, tile.zCoord + 0.5D);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = tile;
            }
        }
        return best;
    }

    static int[] boardOrigin(Object game) {
        Object origin = Reflect.call(game, "getBoardOrigin");
        if (origin == null) {
            return null;
        }
        return new int[] { Reflect.toInt(Reflect.call(origin, "getX")),
                Reflect.toInt(Reflect.call(origin, "getY")),
                Reflect.toInt(Reflect.call(origin, "getZ")) };
    }

    // ---------------------------------------------------------------- clicks

    /** Sends a plain right click on the given block, exactly like a player would. */
    public static void rightClickBlock(Minecraft mc, int x, int y, int z) {
        mc.getNetHandler()
                .addToSendQueue(new C08PacketPlayerBlockPlacement(x, y, z, 1, null, 0.5F, 0.5F, 0.5F));
    }

    /** Sends a plain "start digging" click, i.e. a normal left click with the top face. */
    public static void leftClickBlock(Minecraft mc, int x, int y, int z) {
        mc.getNetHandler()
                .addToSendQueue(new C07PacketPlayerDigging(0, x, y, z, 1));
    }

    private static double reachSq() {
        double reach = LgsudokuMod.reachBlocks;
        return reach * reach;
    }

    private static double distanceSq(EntityPlayer player, int x, int y, int z, double eyeOffset) {
        double dx = player.posX - (x + 0.5D);
        double dy = player.posY - (y + 0.5D) + eyeOffset;
        double dz = player.posZ - (z + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Mirrors the server-side check of {@code processPlayerBlockPlacement}. */
    public static boolean canRightClick(EntityPlayer player, int x, int y, int z) {
        return distanceSq(player, x, y, z, 0.0D) <= reachSq();
    }

    /** Mirrors the server-side check of {@code processPlayerDigging}. */
    public static boolean canLeftClick(EntityPlayer player, int x, int y, int z) {
        return distanceSq(player, x, y, z, DIG_EYE_OFFSET) <= reachSq();
    }

    /**
     * Guard: only ever click a LootGames subordinate block. This keeps the mod from interacting
     * with random blocks if a LootGames update changes the board layout.
     */
    public static boolean isBoardBlock(World world, int x, int y, int z) {
        if (world == null || !world.blockExists(x, y, z)) {
            return false;
        }
        Block block = world.getBlock(x, y, z);
        if (block == null || block == Blocks.air) {
            return false;
        }
        return block.getClass()
                .getName()
                .contains("Subordinate");
    }

    // ---------------------------------------------------------------- sudoku view

    public static boolean available() {
        return Reflect.findClass(SUDOKU_TILE_CLASS) != null;
    }

    public static void invalidate() {
        sudokuCache = null;
        sudokuCacheWorld = null;
        sudokuCacheAge = CACHE_TICKS;
    }

    public static void tickCache(Minecraft mc) {
        if (sudokuCacheAge++ >= CACHE_TICKS) {
            refresh(mc);
        }
    }

    public static BoardView refresh(Minecraft mc) {
        sudokuCacheAge = 0;
        sudokuCacheWorld = mc == null ? null : mc.theWorld;
        sudokuCache = read(mc);
        return sudokuCache;
    }

    public static BoardView current(Minecraft mc) {
        if (sudokuCacheWorld != mc.theWorld) {
            return refresh(mc);
        }
        return sudokuCache;
    }

    private static BoardView read(Minecraft mc) {
        TileEntity tile = nearestTile(mc, SUDOKU_TILE_CLASS);
        if (tile == null) {
            return null;
        }
        try {
            Object game = Reflect.call(tile, "getGame");
            if (game == null) {
                return null;
            }
            Object board = Reflect.call(game, "getBoard");
            if (board == null) {
                board = Reflect.read(game, "board");
            }
            if (board == null) {
                return null;
            }

            int[][] puzzle = toGrid(Reflect.read(board, "puzzle"));
            int[][] player = toGrid(Reflect.read(board, "player"));
            int[][] solution = toGrid(Reflect.read(board, "solution"));
            if (puzzle == null || player == null) {
                return null;
            }

            int[] origin = boardOrigin(game);
            if (origin == null) {
                return null;
            }

            reportedFailure = false;
            return new BoardView(
                    tile,
                    origin[0],
                    origin[1],
                    origin[2],
                    puzzle,
                    player,
                    solution,
                    Reflect.toInt(Reflect.read(game, "currentLevel")));
        } catch (Throwable t) {
            if (!reportedFailure) {
                reportedFailure = true;
                LgsudokuMod.log("无法读取数独棋盘（LootGames 结构可能已变化）", t);
            }
            return null;
        }
    }

    private static int[][] toGrid(Object value) {
        if (!(value instanceof Integer[][])) {
            return null;
        }
        Integer[][] source = (Integer[][]) value;
        if (source.length != 9) {
            return null;
        }
        int[][] grid = new int[9][9];
        for (int r = 0; r < 9; r++) {
            Integer[] row = source[r];
            if (row == null || row.length != 9) {
                return null;
            }
            for (int c = 0; c < 9; c++) {
                Integer cell = row[c];
                grid[r][c] = cell == null ? 0 : cell;
            }
        }
        return grid;
    }

    /** Immutable snapshot of one synced sudoku board. */
    public static final class BoardView {

        public final TileEntity tile;
        /** Board origin (north-west cell), in block coordinates. */
        public final int x;
        public final int y;
        public final int z;
        /**
         * Fixed givens, indexed {@code [x][z]} like LootGames does: {@code puzzle[x][z]} is the
         * block at {@code origin + (x, 0, z)}. 0 means "blank".
         */
        public final int[][] puzzle;
        /** What the player has entered so far (givens are copied in), same {@code [x][z]} order. */
        public final int[][] player;
        /** The complete solution, or {@code null} when the server did not send it. */
        public final int[][] solution;
        /** Value of {@code GameSudoku.currentLevel} on the client (display only). */
        public final int level;
        /** Number of blank cells. */
        public final int blanks;
        /** Number of blank cells already filled in. */
        public final int filled;
        /** Number of cells that came with the puzzle (0 means the board is not generated yet). */
        public final int givens;
        /** Hash of the puzzle grid, used to detect the next level. */
        public final int puzzleKey;

        private int[][] answer;

        BoardView(TileEntity tile, int x, int y, int z, int[][] puzzle, int[][] player, int[][] solution,
                int level) {
            this.tile = tile;
            this.x = x;
            this.y = y;
            this.z = z;
            this.puzzle = puzzle;
            this.player = player;
            this.solution = solution;
            this.level = level;
            this.puzzleKey = Arrays.deepHashCode(puzzle);

            int blank = 0;
            int done = 0;
            int given = 0;
            for (int r = 0; r < 9; r++) {
                for (int c = 0; c < 9; c++) {
                    if (puzzle[r][c] == 0) {
                        blank++;
                        if (player[r][c] != 0) {
                            done++;
                        }
                    } else {
                        given++;
                    }
                }
            }
            this.blanks = blank;
            this.filled = done;
            this.givens = given;
        }

        public int cellX(int xOffset) {
            return x + xOffset;
        }

        public int cellZ(int zOffset) {
            return z + zOffset;
        }

        public boolean hasWork() {
            return blanks > filled;
        }

        /** True once LootGames has generated a puzzle (instead of an all-empty board). */
        public boolean isGenerated() {
            return givens > 0;
        }

        /**
         * The grid that should end up on the board: the solution LootGames synced when it is
         * usable, otherwise one computed from the givens.
         */
        public int[][] answer() {
            if (!isGenerated()) {
                return null;
            }
            if (answer == null) {
                answer = SudokuSolver.solutionFor(puzzle, solution);
            }
            return answer;
        }
    }
}
