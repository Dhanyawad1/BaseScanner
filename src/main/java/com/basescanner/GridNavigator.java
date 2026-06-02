package com.basescanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a sequential snake-pattern grid of positions covering the entire
 * OpFactions world (-5000 to +5000 on both X and Z axes).
 *
 * Step size = 256 blocks = exactly 16 chunks = full /f map width with no gaps.
 * Snake pattern: left-to-right on even rows, right-to-left on odd rows.
 */
public class GridNavigator {

    // World border on Pika OpFactions
    private static final int WORLD_MIN = -5000;
    private static final int WORLD_MAX =  5000;

    // Step = /f map radius * 2 = 8 chunks * 2 * 16 blocks = 256 blocks
    // Set to 128 for 50% overlap (safer, no missed areas)
    public static final int STEP = 256;

    private final List<int[]> positions = new ArrayList<>();
    private int currentIndex = 0;

    public GridNavigator() {
        buildGrid();
    }

    private void buildGrid() {
        positions.clear();
        currentIndex = 0;

        int row = 0;
        for (int z = WORLD_MIN; z <= WORLD_MAX; z += STEP) {
            if (row % 2 == 0) {
                // Even row: left → right
                for (int x = WORLD_MIN; x <= WORLD_MAX; x += STEP) {
                    positions.add(new int[]{x, z});
                }
            } else {
                // Odd row: right → left (snake back)
                for (int x = WORLD_MAX; x >= WORLD_MIN; x -= STEP) {
                    positions.add(new int[]{x, z});
                }
            }
            row++;
        }
    }

    /** Returns the current target position [x, z], or null if done. */
    public int[] getCurrentPosition() {
        if (currentIndex < positions.size()) {
            return positions.get(currentIndex);
        }
        return null;
    }

    /** Advances to the next position and returns it, or null if scan complete. */
    public int[] getNextPosition() {
        currentIndex++;
        return getCurrentPosition();
    }

    public boolean hasNext() {
        return currentIndex < positions.size() - 1;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getTotalPositions() {
        return positions.size();
    }

    /** Restart the scan from the beginning. */
    public void reset() {
        currentIndex = 0;
    }

    /** Jump to a specific index (resume after disconnect). */
    public void setIndex(int index) {
        this.currentIndex = Math.max(0, Math.min(index, positions.size() - 1));
    }

    /** Returns percentage complete (0-100). */
    public int getProgressPercent() {
        if (positions.isEmpty()) return 100;
        return (int)((currentIndex / (double) positions.size()) * 100);
    }
}
