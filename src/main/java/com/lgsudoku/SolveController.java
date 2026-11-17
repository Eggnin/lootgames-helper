package com.lgsudoku;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * One hotkey for all minigames: while a solver runs it stops it, otherwise it starts the solver of
 * the closest LootGames board.
 */
public final class SolveController {

    private SolveController() {}

    public static void toggle(Minecraft mc) {
        if (AutoSolver.isActive()) {
            AutoSolver.toggle(mc);
            return;
        }
        if (MinesweeperSolver.isActive()) {
            MinesweeperSolver.toggle(mc);
            return;
        }
        if (GameOfLightSolver.isActive()) {
            GameOfLightSolver.toggle(mc);
            return;
        }

        SudokuBridge.BoardView sudoku = SudokuBridge.refresh(mc);
        MinesweeperBridge.View mines = MinesweeperBridge.refresh(mc);
        GameOfLightBridge.View gameOfLight = GameOfLightBridge.refresh(mc);

        if (sudoku == null && mines == null && gameOfLight == null) {
            mc.thePlayer.addChatMessage(
                    new ChatComponentText(
                            EnumChatFormatting.AQUA + "[LootGames 助手] "
                                    + EnumChatFormatting.RESET
                                    + "附近没有数独/扫雷/游戏灯棋盘。"));
            return;
        }

        double best = Double.MAX_VALUE;
        int choice = 0;
        if (sudoku != null) {
            best = distanceSq(mc, sudoku.tile);
        }
        if (mines != null) {
            double distance = distanceSq(mc, mines.tile);
            if (distance < best) {
                best = distance;
                choice = 1;
            }
        }
        if (gameOfLight != null && distanceSq(mc, gameOfLight.tile) < best) {
            choice = 2;
        }

        switch (choice) {
            case 1:
                MinesweeperSolver.start(mc);
                break;
            case 2:
                GameOfLightSolver.start(mc);
                break;
            default:
                AutoSolver.start(mc);
                break;
        }
    }

    private static double distanceSq(Minecraft mc, TileEntity tile) {
        return mc.thePlayer.getDistanceSq(tile.xCoord + 0.5D, tile.yCoord + 0.5D, tile.zCoord + 0.5D);
    }
}
