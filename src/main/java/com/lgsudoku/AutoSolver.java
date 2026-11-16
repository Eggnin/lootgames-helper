package com.lgsudoku;

import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * Drives the "fill the board" interaction.
 *
 * <p>
 * LootGames' sudoku accepts <em>increment only</em> from a normal right click
 * ({@code cycleValueAdd}: 0→1→…→9→0), so a cell is filled by sending as many right clicks as
 * the distance between its current value and the wanted one. Every click is verified against
 * the next board snapshot, so a cell the player touches by hand is simply corrected on the
 * next pass.
 *
 * <p>
 * <b>Axis convention:</b> LootGames stores the board as {@code Integer[SIZE][SIZE]} indexed by
 * <em>block</em> coordinates, not by [row][col]:
 * {@code convertToGamePos(p) = Pos2i(p.x - origin.x, p.z - origin.z)} and
 * {@code convertToBlockPos(pos) = origin.offset(pos.x, 0, pos.y)}. So {@code puzzle[x][z]}
 * belongs to the block at {@code (origin.x + x, origin.y, origin.z + z)}.
 */
public final class AutoSolver {

    /** Ticks to wait for a cell to come back from the server before re-checking it. */
    private static final int SETTLE_TICKS = 25;
    /** Back-off for a cell whose clicks had no effect at all (usually "too far for the server"). */
    private static final int NO_EFFECT_BACKOFF = 60;
    /** Consecutive ticks without any board left to fill before the run stops. */
    private static final int IDLE_GIVE_UP = 400;

    private static boolean active;
    private static int tick;

    private static final int[] settle = new int[81];
    private static final int[] probeBefore = new int[81];
    private static final boolean[] probeArmed = new boolean[81];
    private static int noEffectCells;
    private static int planKey;
    private static int[][] target;

    private static int idleTicks;
    private static int boards;
    private static int clicks;
    private static String status = "";
    private static int lastDistanceWarn;
    private static boolean warnedNoEffect;
    private static boolean warnedLayout;
    private static int lastLogTick;

    private AutoSolver() {}

    public static boolean isActive() {
        return active;
    }

    public static String status() {
        return status;
    }

    public static void toggle(Minecraft mc) {
        if (active) {
            stop(mc, "已停止自动解题");
        } else {
            start(mc);
        }
    }

    static void start(Minecraft mc) {
        if (!SudokuBridge.available()) {
            chat(mc, EnumChatFormatting.RED + "没有找到 LootGames，请先安装它。");
            return;
        }

        SudokuBridge.BoardView view = SudokuBridge.refresh(mc);
        if (view == null) {
            chat(
                    mc,
                    EnumChatFormatting.RED + "附近 "
                            + (int) LgsudokuMod.scanRadius
                            + " 格内没有数独棋盘。站到棋盘旁边再按一次。");
            return;
        }

        active = true;
        resetRun();
        status = "准备中…";

        LgsudokuMod.log(
                "sudoku start: origin=(" + view.x
                        + ","
                        + view.y
                        + ","
                        + view.z
                        + ") player=("
                        + (int) mc.thePlayer.posX
                        + ","
                        + (int) mc.thePlayer.posY
                        + ","
                        + (int) mc.thePlayer.posZ
                        + ") blanks="
                        + view.blanks
                        + " givens="
                        + view.givens
                        + " singleplayer="
                        + mc.isSingleplayer());

        chat(mc, EnumChatFormatting.GREEN + "开始解数独（再按一次可停止）。");
    }

    private static void resetRun() {
        tick = 0;
        idleTicks = 0;
        boards = 0;
        clicks = 0;
        planKey = 0;
        target = null;
        noEffectCells = 0;
        lastDistanceWarn = -1000;
        warnedNoEffect = false;
        warnedLayout = false;
        lastLogTick = 0;
        Arrays.fill(settle, 0);
        Arrays.fill(probeArmed, false);
    }

    private static void stop(Minecraft mc, String message) {
        boolean wasActive = active;
        active = false;
        target = null;
        planKey = 0;
        Arrays.fill(settle, 0);
        Arrays.fill(probeArmed, false);

        if (wasActive && mc.thePlayer != null) {
            chat(mc, message + (clicks > 0 ? "（本次共点击 " + clicks + " 次）" : ""));
        }
        status = "";
        resetRun();
    }

    /** Silent reset, used when leaving the world. */
    public static void abort() {
        active = false;
        status = "";
        resetRun();
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
            status = "潜行中暂停（潜行右键是倒退数字）";
            return;
        }

        SudokuBridge.BoardView view = SudokuBridge.refresh(mc);
        if (view == null) {
            status = "附近没有数独棋盘";
            if (++idleTicks > IDLE_GIVE_UP) {
                stop(mc, boards > 0 ? "数独已经结束" : "找不到棋盘，已停止");
            }
            return;
        }

        if (!view.isGenerated()) {
            status = "等待棋盘生成（先右键一次棋盘）";
            return;
        }

        if (target == null || planKey != view.puzzleKey) {
            int[][] solution = view.answer();
            if (solution == null) {
                stop(mc, "无法解开这个棋盘（或答案尚未同步）");
                return;
            }
            target = solution;
            planKey = view.puzzleKey;
            Arrays.fill(settle, 0);
            Arrays.fill(probeArmed, false);
            idleTicks = 0;
            noEffectCells = 0;
            boards++;
        }

        int[] candidates = new int[81];
        int candidateCount = 0;
        int remaining = 0;
        int unreachable = 0;
        int nearestUnreachable = -1;
        double nearestUnreachableDistance = Double.MAX_VALUE;

        for (int index = 0; index < 81; index++) {
            int x = index / 9;
            int z = index % 9;
            if (view.puzzle[x][z] != 0) {
                continue;
            }

            int currentValue = view.player[x][z];
            int wanted = target[x][z];

            // Did the clicks we sent earlier actually land? A value that is still exactly the
            // pre-click value means the server dropped the placement (usually: out of reach).
            if (probeArmed[index] && settle[index] <= tick) {
                probeArmed[index] = false;
                if (currentValue == probeBefore[index]) {
                    onNoEffect(mc, x, z, index);
                }
            }

            if (currentValue == wanted) {
                continue;
            }
            remaining++;

            int blockX = view.cellX(x);
            int blockZ = view.cellZ(z);
            if (!SudokuBridge.canRightClick(mc.thePlayer, blockX, view.y, blockZ)) {
                unreachable++;
                double distance = mc.thePlayer.getDistanceSq(blockX + 0.5D, view.y + 0.5D, blockZ + 0.5D);
                if (distance < nearestUnreachableDistance) {
                    nearestUnreachableDistance = distance;
                    nearestUnreachable = index;
                }
                continue;
            }

            if (settle[index] > tick) {
                continue;
            }
            candidates[candidateCount++] = index;
        }

        int correct = view.blanks - remaining;

        // Nearest first: the server position lags behind ours by a tick, so prefer the cells we
        // are obviously close to and leave the far ones for when the player walks over.
        sortByDistance(mc, view, candidates, candidateCount);

        int budget = Math.max(1, LgsudokuMod.cellsPerTick);
        int acted = 0;
        boolean layoutMismatch = false;

        for (int k = 0; k < candidateCount && acted < budget; k++) {
            int index = candidates[k];
            int x = index / 9;
            int z = index % 9;
            int blockX = view.cellX(x);
            int blockZ = view.cellZ(z);

            if (!SudokuBridge.isBoardBlock(mc.theWorld, blockX, view.y, blockZ)) {
                layoutMismatch = true;
                continue;
            }

            int times = (target[x][z] - view.player[x][z] + 10) % 10;
            for (int n = 0; n < times; n++) {
                SudokuBridge.rightClickBlock(mc, blockX, view.y, blockZ);
            }
            clicks += times;
            probeBefore[index] = view.player[x][z];
            probeArmed[index] = true;
            settle[index] = tick + SETTLE_TICKS;
            acted++;
        }

        if (acted > 0) {
            idleTicks = 0;
        }

        if (layoutMismatch && acted == 0) {
            if (!warnedLayout) {
                warnedLayout = true;
                chat(mc, EnumChatFormatting.YELLOW + "棋盘方块结构对不上（LootGames 版本变化？），已暂停点击。");
            }
            status = "棋盘结构不匹配";
            idleTicks++;
        } else if (unreachable > 0) {
            String where = direction(mc, view, nearestUnreachable);
            status = "还有 " + unreachable + " 格够不到（绿色），请" + where;
            if (tick - lastDistanceWarn > 120) {
                lastDistanceWarn = tick;
                chat(mc, EnumChatFormatting.YELLOW + "还有 " + unreachable + " 格够不到（绿色标出），请" + where + "。");
            }
        } else if (remaining == 0) {
            status = "第 " + boards + " 盘已填满，等待下一盘…";
            idleTicks++;
        } else {
            status = "第 " + boards + " 盘 · " + correct + "/" + view.blanks + " 格";
            if (acted == 0) {
                idleTicks++;
            }
        }

        if (tick - lastLogTick >= 100) {
            lastLogTick = tick;
            LgsudokuMod.log(
                    "sudoku tick=" + tick
                            + " acted=" + acted
                            + " correct=" + correct
                            + "/" + view.blanks
                            + " unreachable=" + unreachable
                            + " noEffect=" + noEffectCells
                            + " clicks=" + clicks
                            + " player=(" + (int) mc.thePlayer.posX
                            + "," + (int) mc.thePlayer.posY
                            + "," + (int) mc.thePlayer.posZ + ")");
        }

        if (idleTicks > IDLE_GIVE_UP) {
            stop(mc, remaining == 0 ? "数独已经结束" : "长时间没有进展，已停止");
        }
    }

    /** A cell we clicked did not change at all: back off instead of hammering it. */
    private static void onNoEffect(Minecraft mc, int x, int z, int index) {
        noEffectCells++;
        settle[index] = tick + NO_EFFECT_BACKOFF;
        LgsudokuMod.log("right click had no effect at cell (" + x + "," + z + ")");
        if (!warnedNoEffect) {
            warnedNoEffect = true;
            chat(
                    mc,
                    EnumChatFormatting.YELLOW
                            + "有格子点了没反应（多半是服务端觉得太远），先把近的填完，够不到的会提示你走过去。");
        }
    }

    private static void sortByDistance(Minecraft mc, SudokuBridge.BoardView view, int[] candidates, int count) {
        double[] distances = new double[count];
        for (int i = 0; i < count; i++) {
            int index = candidates[i];
            int x = index / 9;
            int z = index % 9;
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
    private static String direction(Minecraft mc, SudokuBridge.BoardView view, int index) {
        if (index < 0) {
            return "走到棋盘中间";
        }
        int x = index / 9;
        int z = index % 9;
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
        return EnumChatFormatting.AQUA + "[数独助手] " + EnumChatFormatting.RESET + status;
    }

    private static void chat(Minecraft mc, String message) {
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.AQUA + "[数独助手] " + EnumChatFormatting.RESET + message));
        }
    }
}
