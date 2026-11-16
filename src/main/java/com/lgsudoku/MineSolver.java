package com.lgsudoku;

import java.util.ArrayList;
import java.util.List;

/**
 * Minesweeper constraint solver.
 *
 * <p>
 * LootGames only syncs the numbers of revealed cells, so this is a real solver:
 * <ol>
 * <li>plain counting rules ({@code need == 0} → all safe, {@code need == all} → all mines),</li>
 * <li>the remaining bomb count of the whole board,</li>
 * <li>subset rules between two number constraints,</li>
 * <li>brute force over the connected frontier components, combined with the board's total bomb
 * count (capped; a component is skipped when it would explode).</li>
 * </ol>
 *
 * <p>
 * Every deduction is sound: a cell is only marked {@link #SAFE} when <em>all</em> mine layouts
 * consistent with the current knowledge leave it empty. {@link #guess} is only used when nothing
 * can be proven (LootGames generates "no guess" boards by default, so it should stay unused).
 */
public final class MineSolver {

    public static final byte UNKNOWN = 0;
    public static final byte SAFE = 1;
    public static final byte MINE = 2;

    /** Enumeration is skipped for bigger components (2^n would explode). Tunable for testing. */
    private static final int MAX_COMPONENT = Integer.getInteger("lgsudoku.mineComponent", 24);
    private static final int MAX_NODES = Integer.getInteger("lgsudoku.mineNodes", 120000);

    private MineSolver() {}

    /**
     * Runs deduction until nothing new can be proven.
     *
     * @param size    board side length
     * @param numbers per cell: -1 for hidden, 0..8 for a revealed number
     * @param states  per cell: {@link #UNKNOWN}/{@link #SAFE}/{@link #MINE}; used for hidden cells
     * @param bombs   total bombs on this board
     * @return true when at least one cell changed
     */
    public static boolean deduce(int size, int[] numbers, byte[] states, int bombs) {
        boolean any = false;

        for (int pass = 0; pass < 64; pass++) {
            boolean progress = false;

            if (applyCountingRules(size, numbers, states)) {
                progress = true;
            } else if (applyGlobalRule(size, numbers, states, bombs)) {
                progress = true;
            } else if (applySubsetRules(size, numbers, states)) {
                progress = true;
            } else if (applyEnumeration(size, numbers, states, bombs)) {
                progress = true;
            }

            if (!progress) {
                break;
            }
            any = true;
        }
        return any;
    }

    // ------------------------------------------------------------------ rules

    private static boolean applyCountingRules(int size, int[] numbers, byte[] states) {
        boolean changed = false;

        for (int index = 0; index < size * size; index++) {
            int number = numbers[index];
            if (number < 0) {
                continue;
            }
            int x = index / size;
            int z = index % size;

            int unknown = 0;
            int mines = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                        continue;
                    }
                    int neighbour = nx * size + nz;
                    if (numbers[neighbour] >= 0) {
                        continue;
                    }
                    if (states[neighbour] == MINE) {
                        mines++;
                    } else if (states[neighbour] == UNKNOWN) {
                        unknown++;
                    }
                }
            }

            if (unknown == 0) {
                continue;
            }

            int need = number - mines;
            if (need == 0) {
                changed |= markNeighbours(size, numbers, states, x, z, SAFE);
            } else if (need == unknown) {
                changed |= markNeighbours(size, numbers, states, x, z, MINE);
            }
        }
        return changed;
    }

    private static boolean markNeighbours(int size, int[] numbers, byte[] states, int x, int z, byte state) {
        boolean changed = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                    continue;
                }
                int neighbour = nx * size + nz;
                if (numbers[neighbour] < 0 && states[neighbour] == UNKNOWN) {
                    states[neighbour] = state;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean applyGlobalRule(int size, int[] numbers, byte[] states, int bombs) {
        int knownMines = 0;
        int unknownHidden = 0;

        for (int index = 0; index < size * size; index++) {
            if (numbers[index] >= 0) {
                continue;
            }
            if (states[index] == MINE) {
                knownMines++;
            } else if (states[index] == UNKNOWN) {
                unknownHidden++;
            }
        }

        if (unknownHidden == 0) {
            return false;
        }

        int remaining = bombs - knownMines;
        if (remaining == 0) {
            for (int index = 0; index < size * size; index++) {
                if (numbers[index] < 0 && states[index] == UNKNOWN) {
                    states[index] = SAFE;
                }
            }
            return true;
        }
        if (remaining == unknownHidden) {
            for (int index = 0; index < size * size; index++) {
                if (numbers[index] < 0 && states[index] == UNKNOWN) {
                    states[index] = MINE;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean applySubsetRules(int size, int[] numbers, byte[] states) {
        List<Constraint> constraints = buildConstraints(size, numbers, states);
        if (constraints.size() < 2) {
            return false;
        }

        boolean changed = false;

        for (int i = 0; i < constraints.size(); i++) {
            Constraint a = constraints.get(i);
            for (int j = 0; j < constraints.size(); j++) {
                if (i == j) {
                    continue;
                }
                Constraint b = constraints.get(j);
                if (Math.abs(a.x - b.x) > 2 || Math.abs(a.z - b.z) > 2) {
                    continue;
                }
                if (a.cells.length >= b.cells.length || !isSubset(a.cells, b.cells)) {
                    continue;
                }

                int extra = b.cells.length - a.cells.length;
                int need = b.need - a.need;
                if (need < 0 || need > extra) {
                    continue;
                }

                for (int cell : b.cells) {
                    if (contains(a.cells, cell)) {
                        continue;
                    }
                    byte wanted = need == 0 ? SAFE : need == extra ? MINE : UNKNOWN;
                    if (wanted != UNKNOWN && states[cell] == UNKNOWN) {
                        states[cell] = wanted;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ enumeration

    private static boolean applyEnumeration(int size, int[] numbers, byte[] states, int bombs) {
        List<Constraint> constraints = buildConstraints(size, numbers, states);
        if (constraints.isEmpty()) {
            return false;
        }

        int[] parent = new int[size * size];
        boolean[] inFrontier = new boolean[size * size];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }

        for (Constraint constraint : constraints) {
            int first = constraint.cells[0];
            for (int cell : constraint.cells) {
                union(parent, first, cell);
                inFrontier[cell] = true;
            }
        }

        int[] componentId = new int[size * size];
        java.util.Arrays.fill(componentId, -1);
        int componentCount = 0;

        for (int index = 0; index < size * size; index++) {
            if (!inFrontier[index]) {
                continue;
            }
            int root = find(parent, index);
            if (componentId[root] < 0) {
                componentId[root] = componentCount++;
            }
        }
        if (componentCount == 0) {
            return false;
        }

        int[] counts = new int[componentCount];
        for (int index = 0; index < size * size; index++) {
            if (inFrontier[index]) {
                counts[componentId[find(parent, index)]]++;
            }
        }

        int[][] cellsPerComponent = new int[componentCount][];
        for (int c = 0; c < componentCount; c++) {
            cellsPerComponent[c] = new int[counts[c]];
        }
        int[] fill = new int[componentCount];
        for (int index = 0; index < size * size; index++) {
            if (inFrontier[index]) {
                int c = componentId[find(parent, index)];
                cellsPerComponent[c][fill[c]++] = index;
            }
        }

        List<List<Constraint>> constraintsPerComponent = new ArrayList<List<Constraint>>();
        for (int c = 0; c < componentCount; c++) {
            constraintsPerComponent.add(new ArrayList<Constraint>());
        }
        for (Constraint constraint : constraints) {
            int c = componentId[find(parent, constraint.cells[0])];
            constraintsPerComponent.get(c)
                    .add(constraint);
        }

        int knownMines = 0;
        int outside = 0;
        for (int index = 0; index < size * size; index++) {
            if (numbers[index] >= 0) {
                continue;
            }
            if (states[index] == MINE) {
                knownMines++;
            } else if (states[index] == UNKNOWN && !inFrontier[index]) {
                outside++;
            }
        }
        int remaining = bombs - knownMines;

        // Enumerate every component. Any component that is too big / aborted makes the global
        // combination unsound, so nothing is deduced in that case.
        List<SolutionCounts> results = new ArrayList<SolutionCounts>();
        for (int c = 0; c < componentCount; c++) {
            int[] cells = cellsPerComponent[c];
            if (cells.length == 0 || cells.length > MAX_COMPONENT) {
                return false;
            }

            int[] localIndex = new int[size * size];
            java.util.Arrays.fill(localIndex, -1);
            for (int i = 0; i < cells.length; i++) {
                localIndex[cells[i]] = i;
            }

            List<Constraint> componentConstraints = constraintsPerComponent.get(c);
            int[][] neighbourLists = new int[componentConstraints.size()][];
            int[] needs = new int[componentConstraints.size()];

            for (int i = 0; i < componentConstraints.size(); i++) {
                Constraint constraint = componentConstraints.get(i);
                int[] locals = new int[constraint.cells.length];
                for (int k = 0; k < locals.length; k++) {
                    locals[k] = localIndex[constraint.cells[k]];
                }
                neighbourLists[i] = locals;
                needs[i] = constraint.need;
            }

            Enumerator enumerator = new Enumerator(cells.length, neighbourLists, needs, Math.max(1, remaining + 1));
            enumerator.run();
            if (!enumerator.complete || enumerator.solutions <= 0) {
                return false;
            }
            results.add(enumerator.counts);
        }

        // Combine the components with "outside" cells (which only carry the bomb count).
        double total = 0;
        double[] allComponentWays = convolve(results, null);

        for (int minesInFrontier = 0; minesInFrontier < allComponentWays.length; minesInFrontier++) {
            if (allComponentWays[minesInFrontier] <= 0) {
                continue;
            }
            total += allComponentWays[minesInFrontier] * binomial(outside, remaining - minesInFrontier);
        }
        if (total <= 0) {
            return false;
        }

        boolean changed = false;

        for (int c = 0; c < componentCount; c++) {
            SolutionCounts counts2 = results.get(c);
            double[] others = convolve(results, counts2);

            double[] weightPerMines = new double[counts2.byMines.length];
            for (int k = 0; k < counts2.byMines.length; k++) {
                if (counts2.byMines[k] <= 0) {
                    continue;
                }
                double weight = 0;
                for (int j = 0; j < others.length; j++) {
                    if (others[j] <= 0) {
                        continue;
                    }
                    weight += others[j] * binomial(outside, remaining - k - j);
                }
                weightPerMines[k] = weight;
            }

            int[] cells = cellsPerComponent[c];
            for (int i = 0; i < cells.length; i++) {
                int cell = cells[i];
                if (states[cell] != UNKNOWN) {
                    continue;
                }
                double mineWeight = 0;
                for (int k = 0; k < counts2.byMines.length; k++) {
                    if (counts2.mineByCell[i][k] > 0 && weightPerMines[k] > 0) {
                        mineWeight += counts2.mineByCell[i][k] * weightPerMines[k];
                    }
                }
                if (mineWeight <= 0) {
                    states[cell] = SAFE;
                    changed = true;
                } else if (mineWeight >= total) {
                    states[cell] = MINE;
                    changed = true;
                }
            }
        }

        return changed;
    }

    /** Counts of the enumerated layouts of one component, grouped by their mine total. */
    private static final class SolutionCounts {

        final double[] byMines;
        final double[][] mineByCell;

        SolutionCounts(int cells, int maxMines) {
            this.byMines = new double[maxMines + 1];
            this.mineByCell = new double[cells][maxMines + 1];
        }
    }

    /** Convolves all components, or all but {@code skip}. Index = total mines. */
    private static double[] convolve(List<SolutionCounts> components, SolutionCounts skip) {
        double[] result = new double[] { 1 };
        for (SolutionCounts component : components) {
            if (component == skip) {
                continue;
            }
            double[] next = new double[result.length + component.byMines.length - 1];
            for (int a = 0; a < result.length; a++) {
                if (result[a] <= 0) {
                    continue;
                }
                for (int b = 0; b < component.byMines.length; b++) {
                    if (component.byMines[b] > 0) {
                        next[a + b] += result[a] * component.byMines[b];
                    }
                }
            }
            result = next;
        }
        return result;
    }

    /** C(n, k) as a double; 0 outside the valid range. */
    private static double binomial(int n, int k) {
        if (k < 0 || k > n) {
            return 0;
        }
        if (k == 0 || k == n) {
            return 1;
        }
        k = Math.min(k, n - k);
        double value = 1;
        for (int i = 1; i <= k; i++) {
            value = value * (n - k + i) / i;
        }
        return value;
    }

    /** Enumerates every mine layout of one connected component that satisfies its constraints. */
    private static final class Enumerator {

        private final int cells;
        private final int[][] neighbourLists;
        private final int[] needs;
        private final int[][] cellConstraints;

        private final int[] assignedMines;
        private final int[] assignedCells;
        private final byte[] current;

        final SolutionCounts counts;
        int solutions;
        boolean complete = true;
        private int nodes;

        Enumerator(int cells, int[][] neighbourLists, int[] needs, int maxMines) {
            this.cells = cells;
            this.neighbourLists = neighbourLists;
            this.needs = needs;
            this.assignedMines = new int[neighbourLists.length];
            this.assignedCells = new int[neighbourLists.length];
            this.current = new byte[cells];
            this.counts = new SolutionCounts(cells, maxMines);

            int[][] perCell = new int[cells][];
            for (int cell = 0; cell < cells; cell++) {
                int count = 0;
                for (int[] list : neighbourLists) {
                    if (indexOf(list, cell) >= 0) {
                        count++;
                    }
                }
                int[] ids = new int[count];
                int at = 0;
                for (int i = 0; i < neighbourLists.length; i++) {
                    if (indexOf(neighbourLists[i], cell) >= 0) {
                        ids[at++] = i;
                    }
                }
                perCell[cell] = ids;
            }
            this.cellConstraints = perCell;
        }

        void run() {
            assign(0, 0);
        }

        private void assign(int cell, int mines) {
            if (nodes++ > MAX_NODES) {
                complete = false;
                return;
            }
            if (cell == cells) {
                solutions++;
                if (mines >= counts.byMines.length) {
                    complete = false;
                    return;
                }
                counts.byMines[mines]++;
                for (int i = 0; i < cells; i++) {
                    if (current[i] != 0) {
                        counts.mineByCell[i][mines]++;
                    }
                }
                return;
            }

            // Mine first, then empty.
            for (int value = 1; value >= 0; value--) {
                if (!feasible(cell, value)) {
                    continue;
                }
                current[cell] = (byte) value;
                for (int constraint : cellConstraints[cell]) {
                    assignedCells[constraint]++;
                    if (value == 1) {
                        assignedMines[constraint]++;
                    }
                }

                assign(cell + 1, mines + value);

                for (int constraint : cellConstraints[cell]) {
                    assignedCells[constraint]--;
                    if (value == 1) {
                        assignedMines[constraint]--;
                    }
                }
                current[cell] = 0;

                if (!complete) {
                    return;
                }
            }
        }

        private boolean feasible(int cell, int asMine) {
            for (int constraint : cellConstraints[cell]) {
                int mines = assignedMines[constraint] + (asMine == 1 ? 1 : 0);
                int cellsLeft = neighbourLists[constraint].length - assignedCells[constraint] - 1;
                if (mines > needs[constraint]) {
                    return false;
                }
                if (mines + cellsLeft < needs[constraint]) {
                    return false;
                }
            }
            return true;
        }

        private static int indexOf(int[] array, int value) {
            for (int i = 0; i < array.length; i++) {
                if (array[i] == value) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ------------------------------------------------------------------ guessing

    /**
     * Picks the "least likely to be a bomb" hidden cell, used only when nothing can be proven.
     *
     * @param reachable per cell, whether the player can currently click it
     * @return cell index, or -1 when there is nothing to guess
     */
    public static int guess(int size, int[] numbers, byte[] states, int bombs, boolean[] reachable) {
        int knownMines = 0;
        int unknownHidden = 0;
        for (int index = 0; index < size * size; index++) {
            if (numbers[index] >= 0) {
                continue;
            }
            if (states[index] == MINE) {
                knownMines++;
            } else if (states[index] == UNKNOWN) {
                unknownHidden++;
            }
        }
        if (unknownHidden == 0) {
            return -1;
        }

        float global = Math.max(0, bombs - knownMines) / (float) unknownHidden;

        int best = -1;
        float bestScore = Float.MAX_VALUE;

        for (int index = 0; index < size * size; index++) {
            if (numbers[index] >= 0 || states[index] != UNKNOWN || !reachable[index]) {
                continue;
            }
            int x = index / size;
            int z = index % size;

            float sum = 0;
            int count = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                        continue;
                    }
                    int neighbour = nx * size + nz;
                    int number = numbers[neighbour];
                    if (number <= 0) {
                        continue;
                    }
                    int unknownAround = 0;
                    for (int ax = -1; ax <= 1; ax++) {
                        for (int az = -1; az <= 1; az++) {
                            int cx = nx + ax;
                            int cz = nz + az;
                            if (cx < 0 || cz < 0 || cx >= size || cz >= size) {
                                continue;
                            }
                            int cell = cx * size + cz;
                            if (numbers[cell] < 0 && states[cell] == UNKNOWN) {
                                unknownAround++;
                            }
                        }
                    }
                    if (unknownAround > 0) {
                        sum += number / (float) unknownAround;
                        count++;
                    }
                }
            }

            float score = count == 0 ? global : sum / count;
            if (score < bestScore) {
                bestScore = score;
                best = index;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ helpers

    private static final class Constraint {

        final int[] cells;
        final int need;
        final int x;
        final int z;

        Constraint(int[] cells, int need, int x, int z) {
            this.cells = cells;
            this.need = need;
            this.x = x;
            this.z = z;
        }
    }

    private static List<Constraint> buildConstraints(int size, int[] numbers, byte[] states) {
        List<Constraint> constraints = new ArrayList<Constraint>();

        for (int index = 0; index < size * size; index++) {
            int number = numbers[index];
            if (number <= 0) {
                continue;
            }
            int x = index / size;
            int z = index % size;

            int[] buffer = new int[8];
            int count = 0;
            int mines = 0;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                        continue;
                    }
                    int neighbour = nx * size + nz;
                    if (numbers[neighbour] >= 0) {
                        continue;
                    }
                    if (states[neighbour] == MINE) {
                        mines++;
                    } else if (states[neighbour] == UNKNOWN) {
                        buffer[count++] = neighbour;
                    }
                }
            }

            if (count == 0) {
                continue;
            }
            int need = number - mines;
            if (need <= 0 || need >= count) {
                continue;
            }

            int[] cells = new int[count];
            System.arraycopy(buffer, 0, cells, 0, count);
            java.util.Arrays.sort(cells);
            constraints.add(new Constraint(cells, need, x, z));
        }
        return constraints;
    }

    private static boolean isSubset(int[] small, int[] big) {
        for (int value : small) {
            if (!contains(big, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(int[] array, int value) {
        for (int candidate : array) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    private static int find(int[] parent, int value) {
        while (parent[value] != value) {
            parent[value] = parent[parent[value]];
            value = parent[value];
        }
        return value;
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[rootB] = rootA;
        }
    }
}
