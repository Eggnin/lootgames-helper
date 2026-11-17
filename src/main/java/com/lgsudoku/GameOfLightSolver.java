package com.lgsudoku;

import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * Clientside driver for the LootGames "Game of Light" minigame (the Simon-says memory game).
 *
 * <p>
 * The 3x3 board shows a sequence of symbols and the player has to repeat it. LootGames syncs the
 * {@code StageShowSequence} stage with the whole sequence, so the mod records it and then clicks the
 * same cells (ordinary left clicks) during {@code StageWaitingForSequence}. A run is 4 stages with
 * 5 rounds each; the sequence grows every round and is randomised in the last stage.
 */
public final class GameOfLightSolver {

    /** Ticks between two symbol clicks (~10 per second). */
    private static final int CLICK_INTERVAL = 3;
    /** Ticks to wait before clicking the centre again (start / retry). */
    private static final int START_COOLDOWN = 25;
    private static final int IDLE_GIVE_UP = 600;

    private static boolean active;
    private static int tick;

    /** Remembered sequence (cell offsets {@code x * 3 + z}); survives toggle so a run can resume. */
    private static int[] sequence;
    private static int answered;
    private static int rounds;
    private static int clicks;
    private static String status = "";
    private static int nextClickTick;
    private static int lastStartTick = -1000;
    private static int idleTicks;
    private static boolean warnedNoSequence;
    private static boolean warnedLayout;
    private static String previousStage;
    private static boolean inAnswer;
    private static int lastLogTick;

    private GameOfLightSolver() {}

    public static boolean isActive() {
        return active;
    }

    public static String status() {
        return status;
    }

    public static void toggle(Minecraft mc) {
        if (active) {
            stop(mc, "已停止游戏灯");
        } else {
            start(mc);
        }
    }

    static void start(Minecraft mc) {
        if (!GameOfLightBridge.available()) {
            chat(mc, EnumChatFormatting.RED + "没有找到 LootGames 的游戏灯方块。");
            return;
        }

        GameOfLightBridge.View view = GameOfLightBridge.refresh(mc);
        if (view == null) {
            chat(
                    mc,
                    EnumChatFormatting.RED + "附近 "
                            + (int) LgsudokuMod.scanRadius
                            + " 格内没有游戏灯棋盘。站到棋盘旁边再按一次。");
            return;
        }

        active = true;
        tick = 0;
        idleTicks = 0;
        rounds = 0;
        clicks = 0;
        nextClickTick = 0;
        lastStartTick = -1000;
        warnedNoSequence = false;
        warnedLayout = false;
        previousStage = null;
        lastLogTick = 0;
        status = "准备中…";

        LgsudokuMod.log(
                "game of light start: origin=(" + view.x
                        + ","
                        + view.y
                        + ","
                        + view.z
                        + ") stage="
                        + view.stage
                        + " player=("
                        + (int) mc.thePlayer.posX
                        + ","
                        + (int) mc.thePlayer.posY
                        + ","
                        + (int) mc.thePlayer.posZ + ")");

        chat(mc, EnumChatFormatting.GREEN + "开始解游戏灯（再按一次可停止）。");
    }

    private static void stop(Minecraft mc, String message) {
        boolean wasActive = active;
        active = false;
        if (wasActive && mc.thePlayer != null) {
            chat(mc, message + (rounds > 0 ? "（本次过关 " + rounds + " 轮）" : ""));
        }
        status = "";
        idleTicks = 0;
        rounds = 0;
        clicks = 0;
        warnedNoSequence = false;
    }

    public static void abort() {
        active = false;
        status = "";
        idleTicks = 0;
        rounds = 0;
        clicks = 0;
        sequence = null;
        answered = 0;
        previousStage = null;
        warnedNoSequence = false;
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
            status = "潜行中暂停（潜行时左键不会生效）";
            return;
        }

        GameOfLightBridge.View view = GameOfLightBridge.refresh(mc);
        if (view == null) {
            status = "附近没有游戏灯棋盘";
            if (++idleTicks > IDLE_GIVE_UP) {
                stop(mc, "游戏灯已经结束");
            }
            return;
        }

        boolean enteringShow = "StageShowSequence".equals(view.stage) && !"StageShowSequence".equals(previousStage);
        previousStage = view.stage;
        inAnswer = "StageWaitingForSequence".equals(view.stage);

        if ("StageUnderExpanding".equals(view.stage)) {
            status = "等待棋盘展开…";
            return;
        }

        if ("StageWaitingStart".equals(view.stage)) {
            // A new run (or a retry after a mistake) always starts at the centre.
            if (sequence != null) {
                sequence = null;
                answered = 0;
            }
            if (tick - lastStartTick < START_COOLDOWN) {
                status = "准备开始…";
                return;
            }
            if (!canClick(mc, view, 4)) {
                status = "站到棋盘中间（要能点到中心格）";
                return;
            }
            SudokuBridge.leftClickBlock(mc, view.centerX(), view.y, view.centerZ());
            lastStartTick = tick;
            status = "点击中心开始…";
            return;
        }

        if ("StageShowSequence".equals(view.stage)) {
            if (enteringShow) {
                answered = 0;
                warnedNoSequence = false;
                if (view.sequence != null && view.sequence.length > 0) {
                    sequence = view.sequence;
                }
            } else if (view.sequence != null && view.sequence.length > 0
                    && (sequence == null || !Arrays.equals(sequence, view.sequence))) {
                sequence = view.sequence;
                answered = 0;
            }
            int length = sequence == null ? 0 : sequence.length;
            status = length == 0 ? "看记序列…" : "看记序列 " + Math.min(view.symbolIndex + 1, length) + "/" + length;
            return;
        }

        if ("StageWaitingForSequence".equals(view.stage)) {
            if (sequence == null || sequence.length == 0) {
                status = "还没看到序列，等这一轮结束";
                if (!warnedNoSequence) {
                    warnedNoSequence = true;
                    chat(
                            mc,
                            EnumChatFormatting.YELLOW
                                    + "这次没来得及记录序列（中途按的 K 吧），等这一轮结束会自动继续。");
                }
                return;
            }

            if (answered >= sequence.length) {
                status = "第 " + (rounds + 1) + " 轮输入完，等待下一轮…";
                return;
            }

            if (tick < nextClickTick) {
                return;
            }

            int offset = sequence[answered];
            int x = offset / 3;
            int z = offset % 3;

            if (!canClick(mc, view, offset)) {
                status = "够不到要按的格子，请靠近棋盘";
                if (++idleTicks > 200) {
                    idleTicks = 0;
                    chat(mc, EnumChatFormatting.YELLOW + "请站到游戏灯棋盘中间，3x3 站起来就能全点到。");
                }
                return;
            }
            if (!SudokuBridge.isBoardBlock(mc.theWorld, view.cellX(offset), view.y, view.cellZ(offset))) {
                if (!warnedLayout) {
                    warnedLayout = true;
                    chat(mc, EnumChatFormatting.YELLOW + "棋盘方块结构对不上（LootGames 版本变化？），已暂停点击。");
                }
                status = "棋盘结构不匹配";
                return;
            }

            SudokuBridge.leftClickBlock(mc, view.cellX(offset), view.y, view.cellZ(offset));
            answered++;
            clicks++;
            idleTicks = 0;
            nextClickTick = tick + CLICK_INTERVAL;

            if (answered >= sequence.length) {
                rounds++;
                status = "第 " + rounds + " 轮完成";
            } else {
                status = "输入 " + answered + "/" + sequence.length;
            }

            if (tick - lastLogTick >= 100) {
                lastLogTick = tick;
                LgsudokuMod.log(
                        "game of light tick=" + tick
                                + " stage=" + view.stage
                                + " answered=" + answered + "/" + sequence.length
                                + " rounds=" + rounds
                                + " clicks=" + clicks);
            }
            return;
        }

        status = "等待阶段：" + view.stage;
    }

    private static boolean canClick(Minecraft mc, GameOfLightBridge.View view, int offset) {
        return SudokuBridge.canLeftClick(mc.thePlayer, view.cellX(offset), view.y, view.cellZ(offset));
    }

    /** Cell offset ({@code x * 3 + z}) of the symbol that should be clicked next, or -1. */
    public static int nextCellOffset() {
        if (!active || !inAnswer || sequence == null || answered >= sequence.length) {
            return -1;
        }
        return sequence[answered];
    }

    public static String hudLine() {
        if (!active) {
            return null;
        }
        return EnumChatFormatting.AQUA + "[游戏灯助手] " + EnumChatFormatting.RESET + status;
    }

    private static void chat(Minecraft mc, String message) {
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(
                    new ChatComponentText(
                            EnumChatFormatting.AQUA + "[游戏灯助手] " + EnumChatFormatting.RESET + message));
        }
    }
}
