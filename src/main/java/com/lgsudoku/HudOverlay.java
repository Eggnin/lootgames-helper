package com.lgsudoku;

import net.minecraftforge.client.event.RenderGameOverlayEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** One status line in the top-left corner while a solver is running. */
public final class HudOverlay {

    @SubscribeEvent
    public void onRenderText(RenderGameOverlayEvent.Text event) {
        if (!LgsudokuMod.showHud) {
            return;
        }

        String sudoku = AutoSolver.hudLine();
        if (sudoku != null) {
            event.left.add(sudoku);
        }

        String mines = MinesweeperSolver.hudLine();
        if (mines != null) {
            event.left.add(mines);
        }
    }
}
