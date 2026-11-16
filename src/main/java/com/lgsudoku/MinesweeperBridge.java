package com.lgsudoku;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Reflection-only snapshot of the LootGames minesweeper board.
 *
 * <p>
 * Important: LootGames only syncs the {@code type} of a field once it is revealed
 * ({@code MSBoard.MSField.SYNC_CODEC} omits the type while the field is hidden), so the client
 * genuinely does not know where the bombs are — the board has to be solved.
 *
 * <p>
 * Indexing is {@code field[x][z]}: {@code convertToGamePos(p) = (p.x - origin.x, p.z - origin.z)}
 * and {@code convertToBlockPos(pos) = origin.offset(pos.x, 0, pos.y)}, exactly like the sudoku
 * board.
 */
public final class MinesweeperBridge {

    /** Cell markers for {@link View#type}. */
    public static final byte HIDDEN = -1;
    public static final byte BOMB = -2;

    public static final byte MARK_NONE = 0;
    public static final byte MARK_FLAG = 1;
    public static final byte MARK_QUESTION = 2;

    private static final int CACHE_TICKS = 5;

    private static View cache;
    private static World cacheWorld;
    private static int cacheAge = CACHE_TICKS;
    private static boolean reportedFailure;

    private MinesweeperBridge() {}

    public static boolean available() {
        return Reflect.findClass(SudokuBridge.MS_TILE_CLASS) != null;
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
        TileEntity tile = SudokuBridge.nearestTile(mc, SudokuBridge.MS_TILE_CLASS);
        if (tile == null) {
            return null;
        }

        try {
            Object game = Reflect.call(tile, "getGame");
            if (game == null) {
                return null;
            }

            boolean generated = Reflect.toBool(Reflect.read(game, "cIsGenerated"));

            Object board = Reflect.call(game, "getBoard");
            if (board == null) {
                return null;
            }

            int size = Reflect.toInt(Reflect.call(board, "size"));
            int bombs = Reflect.toInt(Reflect.call(board, "getBombCount"));
            if (size < 3 || size > 32) {
                return null;
            }

            int[] origin = SudokuBridge.boardOrigin(game);
            if (origin == null) {
                return null;
            }

            byte[] type = new byte[size * size];
            byte[] mark = new byte[size * size];
            java.util.Arrays.fill(type, HIDDEN);

            if (generated) {
                Object raw = Reflect.read(board, "board");
                if (!(raw instanceof Object[])) {
                    return null;
                }
                Object[] rows = (Object[]) raw;
                if (rows.length < size) {
                    return null;
                }
                for (int x = 0; x < size; x++) {
                    Object rowObject = rows[x];
                    if (!(rowObject instanceof Object[])) {
                        return null;
                    }
                    Object[] row = (Object[]) rowObject;
                    for (int z = 0; z < size; z++) {
                        Object field = row[z];
                        if (field == null) {
                            continue;
                        }
                        int index = x * size + z;

                        Object markObject = Reflect.call(field, "getMark");
                        mark[index] = (byte) (markObject instanceof Enum ? ((Enum<?>) markObject).ordinal()
                                : MARK_NONE);

                        if (Reflect.toBool(Reflect.call(field, "isHidden"))) {
                            type[index] = HIDDEN;
                        } else {
                            Object typeObject = Reflect.call(field, "getType");
                            int id = Reflect.toInt(Reflect.call(typeObject, "getId"));
                            type[index] = (byte) (id < 0 ? BOMB : id);
                        }
                    }
                }
            }

            reportedFailure = false;
            return new View(tile, origin[0], origin[1], origin[2], size, generated, bombs, type, mark);
        } catch (Throwable t) {
            if (!reportedFailure) {
                reportedFailure = true;
                LgsudokuMod.log("无法读取扫雷棋盘（LootGames 结构可能已变化）", t);
            }
            return null;
        }
    }

    /** Immutable snapshot of the synced minesweeper board. */
    public static final class View {

        public final TileEntity tile;
        public final int x;
        public final int y;
        public final int z;
        /** Current board size (5/7/9/... blocks per side, centred inside the allocated field). */
        public final int size;
        /** {@code GameMineSweeper.cIsGenerated}: false means "click a cell to generate the board". */
        public final boolean generated;
        public final int bombs;
        /** Per cell: {@link #HIDDEN}, {@link #BOMB} or 0..8 (revealed number). */
        public final byte[] type;
        /** Per cell: {@link #MARK_NONE}/{@link #MARK_FLAG}/{@link #MARK_QUESTION}. */
        public final byte[] mark;
        public final int revealed;
        public final int hidden;

        View(TileEntity tile, int x, int y, int z, int size, boolean generated, int bombs, byte[] type, byte[] mark) {
            this.tile = tile;
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
            this.generated = generated;
            this.bombs = bombs;
            this.type = type;
            this.mark = mark;

            int shown = 0;
            for (byte value : type) {
                if (value != HIDDEN) {
                    shown++;
                }
            }
            this.revealed = shown;
            this.hidden = size * size - shown;
        }

        public int index(int xOffset, int zOffset) {
            return xOffset * size + zOffset;
        }

        public int cellX(int xOffset) {
            return x + xOffset;
        }

        public int cellZ(int zOffset) {
            return z + zOffset;
        }

        public boolean isBombShown() {
            for (byte value : type) {
                if (value == BOMB) {
                    return true;
                }
            }
            return false;
        }

        /** Number array for {@link MineSolver}: -1 for hidden cells, 0..8 for revealed ones. */
        public int[] numbers() {
            int[] numbers = new int[size * size];
            for (int i = 0; i < numbers.length; i++) {
                byte value = type[i];
                numbers[i] = value == HIDDEN || value == BOMB ? -1 : value;
            }
            return numbers;
        }
    }
}
