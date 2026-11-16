package com.lgsudoku;

/**
 * A tiny backtracking sudoku solver.
 *
 * <p>
 * LootGames already syncs the finished grid to the client, so this solver is only a
 * fallback: it is used when the {@code solution} array is missing/empty (older builds or
 * future protocol changes) and it validates whatever the server sent before trusting it.
 */
public final class SudokuSolver {

    private SudokuSolver() {}

    /**
     * Returns the grid to fill in for the given board: the server-provided solution when it is
     * complete and consistent with the givens, otherwise a freshly computed one. Returns
     * {@code null} when the puzzle cannot be solved.
     */
    public static int[][] solutionFor(int[][] puzzle, int[][] known) {
        if (isComplete(known) && consistent(known, puzzle)) {
            return copy(known);
        }
        return solve(puzzle);
    }

    /** Solves the grid, or returns {@code null} if there is no solution. */
    public static int[][] solve(int[][] puzzle) {
        int[] rows = new int[9];
        int[] cols = new int[9];
        int[] boxes = new int[9];

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int value = puzzle[r][c];
                if (value == 0) {
                    continue;
                }
                if (value < 1 || value > 9) {
                    return null;
                }
                int bit = 1 << value;
                if ((rows[r] & bit) != 0 || (cols[c] & bit) != 0 || (boxes[box(r, c)] & bit) != 0) {
                    return null;
                }
                rows[r] |= bit;
                cols[c] |= bit;
                boxes[box(r, c)] |= bit;
            }
        }

        int[][] grid = copy(puzzle);
        return search(grid, rows, cols, boxes) ? grid : null;
    }

    private static boolean search(int[][] grid, int[] rows, int[] cols, int[] boxes) {
        int bestRow = -1;
        int bestCol = -1;
        int bestMask = 0;
        int bestCount = 10;

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (grid[r][c] != 0) {
                    continue;
                }
                int mask = ~(rows[r] | cols[c] | boxes[box(r, c)]) & 0x3FE; // bits 1..9
                int count = Integer.bitCount(mask);
                if (count == 0) {
                    return false;
                }
                if (count < bestCount) {
                    bestCount = count;
                    bestRow = r;
                    bestCol = c;
                    bestMask = mask;
                }
            }
        }

        if (bestRow < 0) {
            return true;
        }

        int b = box(bestRow, bestCol);
        for (int value = 1; value <= 9; value++) {
            int bit = 1 << value;
            if ((bestMask & bit) == 0) {
                continue;
            }
            grid[bestRow][bestCol] = value;
            rows[bestRow] |= bit;
            cols[bestCol] |= bit;
            boxes[b] |= bit;

            if (search(grid, rows, cols, boxes)) {
                return true;
            }

            grid[bestRow][bestCol] = 0;
            rows[bestRow] &= ~bit;
            cols[bestCol] &= ~bit;
            boxes[b] &= ~bit;
        }
        return false;
    }

    private static int box(int row, int col) {
        return (row / 3) * 3 + col / 3;
    }

    /** Every cell holds 1..9 and every row/column/box uses each digit exactly once. */
    public static boolean isComplete(int[][] grid) {
        if (grid == null || grid.length != 9) {
            return false;
        }
        for (int i = 0; i < 9; i++) {
            int rowMask = 0;
            int colMask = 0;
            int boxMask = 0;
            for (int j = 0; j < 9; j++) {
                int rowValue = grid[i][j];
                int colValue = grid[j][i];
                int boxValue = grid[(i / 3) * 3 + j / 3][(i % 3) * 3 + j % 3];
                if (rowValue < 1 || rowValue > 9 || colValue < 1 || colValue > 9 || boxValue < 1 || boxValue > 9) {
                    return false;
                }
                rowMask |= 1 << rowValue;
                colMask |= 1 << colValue;
                boxMask |= 1 << boxValue;
            }
            if (rowMask != 0x3FE || colMask != 0x3FE || boxMask != 0x3FE) {
                return false;
            }
        }
        return true;
    }

    /** Every given of {@code puzzle} appears unchanged in {@code grid}. */
    public static boolean consistent(int[][] grid, int[][] puzzle) {
        if (grid == null || puzzle == null) {
            return false;
        }
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (puzzle[r][c] != 0 && puzzle[r][c] != grid[r][c]) {
                    return false;
                }
            }
        }
        return true;
    }

    public static int[][] copy(int[][] source) {
        int[][] copy = new int[9][9];
        for (int r = 0; r < 9; r++) {
            System.arraycopy(source[r], 0, copy[r], 0, 9);
        }
        return copy;
    }
}
