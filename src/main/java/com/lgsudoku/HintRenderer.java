package com.lgsudoku;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * In-world hints.
 *
 * <ul>
 * <li>Sudoku: the answer is painted flat onto every empty cell, using the same coordinate frame as
 * LootGames' own {@code SudokuRenderer} (origin + rotate 90° about X), so it lines up with the
 * game's numbers. Cells that still need clicks get a green tile — the ones the solver cannot reach
 * from where the player stands stay green, which makes "walk over there" obvious.</li>
 * <li>Minesweeper: deduced safe cells get a green tile, deduced mines a red one.</li>
 * </ul>
 */
public final class HintRenderer {

    private static final int COLOR_EMPTY = 0xC000E070;
    private static final int COLOR_WRONG = 0xC0FF6060;
    /** LootGames draws its numbers at local z = -0.02 (i.e. 0.02 above the floor). */
    private static final float SURFACE_OFFSET = -0.06F;
    private static final float SCALE = 0.07F;

    private static final int TILE_Y_OFFSET = 1;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!LgsudokuMod.showHints) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        renderSudoku(mc);
        renderMinesweeper(mc);
        renderGameOfLight(mc);
    }

    private void renderSudoku(Minecraft mc) {
        SudokuBridge.BoardView view = SudokuBridge.current(mc);
        if (view == null) {
            return;
        }

        int[][] answer = view.answer();
        if (answer == null) {
            return;
        }

        int[] todoX = new int[81];
        int[] todoZ = new int[81];
        int todo = 0;

        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                if (view.puzzle[x][z] == 0 && view.player[x][z] != answer[x][z]) {
                    todoX[todo] = view.cellX(x);
                    todoZ[todo] = view.cellZ(z);
                    todo++;
                }
            }
        }

        // Green tiles first, so the answer digits stay readable on top of them.
        drawFlatTiles(todoX, todoZ, todo, view.y, 80, 230, 120, 70);

        FontRenderer font = mc.fontRenderer;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glTranslatef(
                (float) (view.x - RenderManager.renderPosX),
                // LootGames itself renders the board from "origin + 1" in Y (the top surface of
                // the floor block); without this the digits would be buried inside the block.
                (float) (view.y + TILE_Y_OFFSET - RenderManager.renderPosY),
                (float) (view.z - RenderManager.renderPosZ));
        GL11.glRotatef(90.0F, 1.0F, 0.0F, 0.0F);

        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                if (view.puzzle[x][z] != 0) {
                    continue;
                }

                int wanted = answer[x][z];
                int current = view.player[x][z];
                if (wanted <= 0 || current == wanted) {
                    continue;
                }

                String text = Integer.toString(wanted);
                int color = current == 0 ? COLOR_EMPTY : COLOR_WRONG;
                int width = font.getStringWidth(text);

                GL11.glPushMatrix();
                GL11.glTranslatef(x + 0.5F, z + 0.5F, SURFACE_OFFSET);
                GL11.glScalef(SCALE, SCALE, SCALE);
                font.drawString(text, -width / 2, -font.FONT_HEIGHT / 2, color, false);
                GL11.glPopMatrix();
            }
        }

        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    private void renderMinesweeper(Minecraft mc) {
        MinesweeperBridge.View view = MinesweeperBridge.current(mc);
        if (view == null || !view.generated || view.isBombShown()) {
            return;
        }

        byte[] states = MinesweeperSolver.statesFor(view);
        if (states == null) {
            return;
        }

        int[] safeX = new int[view.type.length];
        int[] safeZ = new int[view.type.length];
        int safe = 0;
        int[] mineX = new int[view.type.length];
        int[] mineZ = new int[view.type.length];
        int mine = 0;

        for (int index = 0; index < view.type.length; index++) {
            if (view.type[index] != MinesweeperBridge.HIDDEN || view.mark[index] == MinesweeperBridge.MARK_FLAG) {
                continue;
            }
            int x = index / view.size;
            int z = index % view.size;
            if (states[index] == MineSolver.SAFE) {
                safeX[safe] = view.cellX(x);
                safeZ[safe] = view.cellZ(z);
                safe++;
            } else if (states[index] == MineSolver.MINE) {
                mineX[mine] = view.cellX(x);
                mineZ[mine] = view.cellZ(z);
                mine++;
            }
        }

        drawFlatTiles(safeX, safeZ, safe, view.y, 80, 230, 120, 70);
        drawFlatTiles(mineX, mineZ, mine, view.y, 240, 70, 70, 90);
    }

    /** Highlights the Game of Light cell that has to be clicked next. */
    private void renderGameOfLight(Minecraft mc) {
        GameOfLightBridge.View view = GameOfLightBridge.current(mc);
        if (view == null) {
            return;
        }

        int offset = GameOfLightSolver.nextCellOffset();
        if (offset < 0) {
            return;
        }

        drawFlatTiles(
                new int[] { view.cellX(offset) },
                new int[] { view.cellZ(offset) },
                1,
                view.y,
                80,
                230,
                120,
                90);
    }

    /** Draws flat translucent squares slightly above the given block layer. */
    private static void drawFlatTiles(int[] xs, int[] zs, int count, int blockY, int r, int g, int b, int a) {
        if (count == 0) {
            return;
        }

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glTranslatef(
                (float) -RenderManager.renderPosX,
                (float) -RenderManager.renderPosY,
                (float) -RenderManager.renderPosZ);

        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA(r, g, b, a);

        double y = blockY + TILE_Y_OFFSET + 0.02D;
        for (int i = 0; i < count; i++) {
            double x0 = xs[i] + 0.2D;
            double x1 = xs[i] + 0.8D;
            double z0 = zs[i] + 0.2D;
            double z1 = zs[i] + 0.8D;
            tessellator.addVertex(x0, y, z0);
            tessellator.addVertex(x0, y, z1);
            tessellator.addVertex(x1, y, z1);
            tessellator.addVertex(x1, y, z0);
        }

        tessellator.draw();
        GL11.glDepthMask(true);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }
}
