package com.lgsudoku;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * Clientside driver for the LootGames minesweeper minigame.
 *
 * <p>
 * LootGames hides the type of every unrevealed field when syncing
 * ({@code MSBoard.MSField.SYNC_CODEC} omits {@code type} while {@code hidden}), so the board has
 * to be solved from the revealed numbers. Interaction is the ordinary one:
 * <ul>
 * <li>left click (status 0 of {@code C07PacketPlayerDigging}) reveals a cell,</li>
 * <li>right click cycles the mark (only needed to clear flags that block a reveal).</li>
 * </ul>
 * A level is won once every non-bomb cell is revealed; bombs never have to be flagged.
 */
public final class MinesweeperSolver {

    /** Ticks to wait after a "generate board" click before trying again. */
    private static final int GENERATE_COOLDOWN = 25;
    /** Ticks between two guess clicks, so a stale snapshot cannot double click. */
    private static final int GUESS_COOLDOWN = 6;
    private static final int IDLE_GIVE_UP = 600;

    private static boolean active;
    private static int tick;

    private static int boards;
    private static int reveals;
    private static int guesses;
    private static int idleTicks;
    private static int lastGenerateTick = -1000;
    private static int lastGuessTick = -1000;
    private static String status = "";

    private static int cachedSignature;
    private static int cachedSize;
    private static int[] cachedNumbers;
    private static byte[] cachedStates;

    private static int warnTick = -1000;
    private static boolean warnedLayout;

    private MinesweeperSolver() {}

    public static boolean isActive() {
        return active;
    }

    public static String status() {
        return status;
    }

    public static void toggle(Minecraft mc) {
        if (active) {
            stop(mc, "已停止扫雷");
        } else {
            start(mc);
        }
    }

    static void start(Minecraft mc) {
        if (!MinesweeperBridge.available()) {
            chat(mc, EnumChatFormatting.RED + "没有找到 LootGames 的扫雷方块。");
            return;
        }

        MinesweeperBridge.View view = MinesweeperBridge.refresh(mc);
        if (view == null) {
            chat(
                    mc,
                    EnumChatFormatting.RED + "附近 "
                            + (int) LgsudokuMod.scanRadius
                            + " 格内没有扫雷棋盘。站到棋盘旁边再按一次。");
            return;
        }

        active = true;
        resetRun();
        status = "准备中…";
        LgsudokuMod.log(
                "minesweeper start: origin=(" + view.x
                        + ","
                        + view.y
                        + ","
                        + view.z
                        + ") size="
                        + view.size
                        + " bombs="
                        + view.bombs
                        + " generated="
                        + view.generated);
        chat(mc, EnumChatFormatting.GREEN + "开始解扫雷（再按一次可停止）。");
    }

    private static void resetRun() {
        tick = 0;
        boards = 0;
        reveals = 0;
        guesses = 0;
        idleTicks = 0;
        lastGenerateTick = -1000;
        lastGuessTick = -1000;
        warnTick = -1000;
        warnedLayout = false;
        cachedStates = null;
        cachedNumbers = null;
        cachedSignature = 0;
    }

    private static void stop(Minecraft mc, String message) {
        boolean wasActive = active;
        active = false;
        if (wasActive && mc.thePlayer != null) {
            chat(
                    mc,
                    message + (reveals > 0 ? "（本次开格 " + reveals + " 次，猜测 " + guesses + " 次）" : ""));
        }
        status = "";
        resetRun();
    }

    public static void abort() {
        active = false;
        status = "";
        resetRun();
    }

    /** Deduced cell states for the given snapshot, cached by board content. */
    static byte[] statesFor(MinesweeperBridge.View view) {
        if (view == null || !view.generated) {
            return null;
        }
        int signature = signature(view);
        if (cachedStates == null || signature != cachedSignature || cachedSize != view.size) {
            int[] numbers = view.numbers();
            byte[] states = new byte[numbers.length];
            MineSolver.deduce(view.size, numbers, states, view.bombs);
            cachedNumbers = numbers;
            cachedStates = states;
            cachedSignature = signature;
            cachedSize = view.size;
        }
        return cachedStates;
    }

    private static int signature(MinesweeperBridge.View view) {
        int hash = 17;
        hash = hash * 31 + view.size;
        hash = hash * 31 + (view.generated ? 1 : 0);
        hash = hash * 31 + view.bombs;
        for (int i = 0; i < view.type.length; i++) {
            hash = hash * 31 + view.type[i];
            hash = hash * 31 + view.mark[i];
        }
        return hash;
    }

    public static void tick(Minecraft mc) {
        if (!active) {
            return;
        }
        tick++;

        if (mc.theWorld == null || mc.thePlayer == null) {
            abort();
            return;
        }
        if (mc.currentScreen != null) {
            status = "界面已打开，暂停";
            return;
        }
        if (mc.thePlayer.isSneaking()) {
            status = "潜行中暂停（潜行时左键不会翻格）";
            return;
        }

        MinesweeperBridge.View view = MinesweeperBridge.refresh(mc);
        if (view == null) {
            status = "附近没有扫雷棋盘";
            if (++idleTicks > IDLE_GIVE_UP) {
                stop(mc, boards > 0 ? "扫雷已经结束" : "找不到棋盘，已停止");
            }
            return;
        }

        if (!view.generated) {
            generateBoard(mc, view);
            return;
        }

        if (view.isBombShown()) {
            status = "踩到雷了，等待重开…";
            return;
        }

        byte[] states = statesFor(view);
        if (states == null) {
            return;
        }

        int budget = Math.max(1, LgsudokuMod.revealPerTick);
        int clicked = 0;
        int unreachable = 0;
        int firstUnreachable = -1;
        double firstUnreachableDistance = Double.MAX_VALUE;
        int remaining = 0;

        int[] candidates = new int[view.type.length];
        int candidateCount = 0;

        for (int index = 0; index < view.type.length; index++) {
            if (view.type[index] != MinesweeperBridge.HIDDEN) {
                continue;
            }
            remaining++;
            if (states[index] != MineSolver.SAFE) {
                continue;
            }

            int x = index / view.size;
            int z = index % view.size;
            int blockX = view.cellX(x);
            int blockZ = view.cellZ(z);

            if (!SudokuBridge.canLeftClick(mc.thePlayer, blockX, view.y, blockZ)) {
                unreachable++;
                double distance = mc.thePlayer.getDistanceSq(blockX + 0.5D, view.y + 0.5D, blockZ + 0.5D);
                if (distance < firstUnreachableDistance) {
                    firstUnreachableDistance = distance;
                    firstUnreachable = index;
                }
                continue;
            }
            candidates[candidateCount++] = index;
        }

        // Nearest first: the server's copy of our position lags a tick, so edge cells can be
        // dropped while walking. Clear what is obviously in range first.
        sortByDistance(mc, view, candidates, candidateCount);

        for (int k = 0; k < candidateCount && clicked < budget; k++) {
            int index = candidates[k];
            int x = index / view.size;
            int z = index % view.size;
            int blockX = view.cellX(x);
            int blockZ = view.cellZ(z);

            if (!SudokuBridge.isBoardBlock(mc.theWorld, blockX, view.y, blockZ)) {
                layoutProblem(mc);
                return;
            }

            revealCell(mc, view, index, blockX, blockZ);
            reveals++;
            clicked++;
        }

        if (clicked > 0) {
            idleTicks = 0;
            status = "第 " + Math.max(1, boards) + " 盘 · 已开 " + view.revealed + "/" + (view.type.length - view.bombs)
                    + " 格";
            return;
        }

        if (remaining == 0) {
            status = "本盘已开完，等待下一盘…";
            if (++idleTicks > IDLE_GIVE_UP) {
                stop(mc, "扫雷已经结束");
            }
            return;
        }

        if (unreachable > 0) {
            String where = direction(mc, view, firstUnreachable);
            status = "还有 " + unreachable + " 格够不到，请" + where;
            if (tick - warnTick > 120) {
                warnTick = tick;
                chat(
                        mc,
                        EnumChatFormatting.YELLOW + "请" + where + "（扫雷盘比 5 格点击范围大，需要走位）。");
            }
            return;
        }

        // Nothing can be proven: pick the least dangerous unknown cell.
        if (tick - lastGuessTick <= GUESS_COOLDOWN) {
            status = "没线索了，试一格…";
            return;
        }

        boolean[] reachable = new boolean[view.type.length];
        for (int index = 0; index < reachable.length; index++) {
            if (view.type[index] != MinesweeperBridge.HIDDEN) {
                continue;
            }
            int x = index / view.size;
            int z = index % view.size;
            reachable[index] = SudokuBridge.canLeftClick(mc.thePlayer, view.cellX(x), view.y, view.cellZ(z));
        }

        int guess = MineSolver.guess(view.size,
                cachedNumbers == null ? view.numbers() : cachedNumbers,
                states,
                view.bombs,
                reachable);
        if (guess < 0) {
            status = "没线索了，请靠近棋盘";
            return;
        }

        int gx = guess / view.size;
        int gz = guess % view.size;
        int blockX = view.cellX(gx);
        int blockZ = view.cellZ(gz);
        if (!SudokuBridge.isBoardBlock(mc.theWorld, blockX, view.y, blockZ)) {
            layoutProblem(mc);
            return;
        }

        revealCell(mc, view, guess, blockX, blockZ);
        guesses++;
        lastGuessTick = tick;
        status = "没线索了，试一格…（已猜 " + guesses + " 次）";
    }

    private static void generateBoard(Minecraft mc, MinesweeperBridge.View view) {
        if (tick - lastGenerateTick <= GENERATE_COOLDOWN) {
            status = "生成棋盘…";
            return;
        }

        int best = -1;
        double bestDistance = Double.MAX_VALUE;

        for (int index = 0; index < view.type.length; index++) {
            int x = index / view.size;
            int z = index % view.size;
            int blockX = view.cellX(x);
            int blockZ = view.cellZ(z);
            if (!SudokuBridge.canLeftClick(mc.thePlayer, blockX, view.y, blockZ)) {
                continue;
            }
            double distance = mc.thePlayer.getDistanceSq(blockX + 0.5D, view.y + 0.5D, blockZ + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }

        if (best < 0) {
            status = "请走到棋盘上（要能点到）";
            return;
        }

        int x = best / view.size;
        int z = best % view.size;
        int blockX = view.cellX(x);
        int blockZ = view.cellZ(z);
        if (!SudokuBridge.isBoardBlock(mc.theWorld, blockX, view.y, blockZ)) {
            layoutProblem(mc);
            return;
        }

        SudokuBridge.leftClickBlock(mc, blockX, view.y, blockZ);
        lastGenerateTick = tick;
        boards++;
        status = "生成第 " + boards + " 盘…";
    }

    /** Reveals one cell, clearing a player flag/question mark first (both block a reveal). */
    private static void revealCell(Minecraft mc, MinesweeperBridge.View view, int index, int blockX, int blockZ) {
        byte mark = view.mark[index];
        if (mark == MinesweeperBridge.MARK_FLAG) {
            SudokuBridge.rightClickBlock(mc, blockX, view.y, blockZ);
            SudokuBridge.rightClickBlock(mc, blockX, view.y, blockZ);
        } else if (mark == MinesweeperBridge.MARK_QUESTION) {
            SudokuBridge.rightClickBlock(mc, blockX, view.y, blockZ);
        }
        SudokuBridge.leftClickBlock(mc, blockX, view.y, blockZ);
    }

    private static void layoutProblem(Minecraft mc) {
        if (!warnedLayout) {
            warnedLayout = true;
            chat(mc, EnumChatFormatting.YELLOW + "棋盘方块结构对不上（LootGames 版本变化？），已暂停点击。");
            LgsudokuMod.log("minesweeper board block mismatch");
        }
        status = "棋盘结构不匹配";
    }

    private static void sortByDistance(Minecraft mc, MinesweeperBridge.View view, int[] candidates, int count) {
        double[] distances = new double[count];
        for (int i = 0; i < count; i++) {
            int index = candidates[i];
            int x = index / view.size;
            int z = index % view.size;
            distances[i] = mc.thePlayer
                    .getDistanceSq(view.cellX(x) + 0.5D, view.y + 0.5D, view.cellZ(z) + 0.5D);
        }
        for (int i = 1; i < count; i++) {
            int index = candidates[i];
            double distance = distances[i];
            int j = i - 1;
            while (j >= 0 && distances[j] > distance) {
                candidates[j + 1] = candidates[j];
                distances[j + 1] = distances[j];
                j--;
            }
            candidates[j + 1] = index;
            distances[j + 1] = distance;
        }
    }

    /** Rough compass hint towards a board cell, in world axes: +X east, +Z south. */
    private static String direction(Minecraft mc, MinesweeperBridge.View view, int index) {
        if (index < 0) {
            return "走到棋盘中间";
        }
        int x = index / view.size;
        int z = index % view.size;
        double dx = view.cellX(x) + 0.5D - mc.thePlayer.posX;
        double dz = view.cellZ(z) + 0.5D - mc.thePlayer.posZ;

        String horizontal = dx > 1.5 ? "东" : dx < -1.5 ? "西" : "";
        String vertical = dz > 1.5 ? "南" : dz < -1.5 ? "北" : "";
        String compass = horizontal + vertical;
        if (compass.isEmpty()) {
            return "走到棋盘中间";
        }
        int distance = (int) Math.max(Math.abs(dx), Math.abs(dz));
        return "往" + compass + "走 " + distance + " 格";
    }

    public static String hudLine() {
        if (!active) {
            return null;
        }
        return EnumChatFormatting.AQUA + "[扫雷助手] " + EnumChatFormatting.RESET + status;
    }

    private static void chat(Minecraft mc, String message) {
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.AQUA + "[扫雷助手] " + EnumChatFormatting.RESET + message));
        }
    }
}
