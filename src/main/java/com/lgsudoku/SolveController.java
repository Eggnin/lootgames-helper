package com.lgsudoku;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * One hotkey for both minigames: while a solver runs it stops it, otherwise it starts the solver
 * of the closest LootGames board.
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

        SudokuBridge.refresh(mc);
        MinesweeperBridge.refresh(mc);

        SudokuBridge.BoardView sudoku = SudokuBridge.current(mc);
        MinesweeperBridge.View mines = MinesweeperBridge.current(mc);

        if (sudoku == null && mines == null) {
            mc.thePlayer.addChatMessage(
                    new ChatComponentText(
                            EnumChatFormatting.AQUA + "[LootGames 助手] "
                                    + EnumChatFormatting.RESET
                                    + "附近没有数独/扫雷棋盘。"));
            return;
        }

        boolean preferMines = mines != null
                && (sudoku == null || distanceSq(mc, mines.tile) < distanceSq(mc, sudoku.tile));
        if (preferMines) {
            MinesweeperSolver.start(mc);
        } else {
            AutoSolver.start(mc);
        }
    }

    private static double distanceSq(Minecraft mc, TileEntity tile) {
        return mc.thePlayer.getDistanceSq(tile.xCoord + 0.5D, tile.yCoord + 0.5D, tile.zCoord + 0.5D);
    }
}
