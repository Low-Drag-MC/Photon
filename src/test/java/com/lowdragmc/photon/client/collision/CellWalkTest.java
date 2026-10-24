package com.lowdragmc.photon.client.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CellWalkTest {
    private static final int MAX_CELLS = 400;

    @Test
    void visitsTheCellsVanillaDoes() {
        var random = new Random(5);
        int[][] diagonals = {{1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {1, 1, 1}, {1, -1, 1}, {-1, -1, -1}, {1, 0, -1}};
        for (int i = 0; i < 30_000; i++) {
            double far = Math.pow(10, random.nextInt(7)) * (random.nextBoolean() ? 1 : -1);
            double ox, oy, oz, ex, ey, ez;
            if (i % 3 == 0) {
                // from a block centre at exactly 45 degrees: through edges and corners
                var d = diagonals[random.nextInt(diagonals.length)];
                double length = 1 + random.nextInt(12);
                ox = Math.floor(far * random.nextDouble()) + 0.5;
                oy = random.nextInt(300) - 64 + 0.5;
                oz = Math.floor(far * random.nextDouble()) + 0.5;
                ex = ox + d[0] * length;
                ey = oy + d[1] * length;
                ez = oz + d[2] * length;
            } else {
                ox = far * random.nextDouble();
                oy = random.nextDouble() * 300 - 64;
                oz = far * random.nextDouble();
                ex = ox + (random.nextDouble() - 0.5) * 40;
                ey = oy + (random.nextDouble() - 0.5) * 40;
                ez = oz + (random.nextDouble() - 0.5) * 40;
            }
            var expected = new ArrayList<Long>();
            BlockGetter.traverseBlocks(new Vec3(ox, oy, oz), new Vec3(ex, ey, ez), expected, (cells, pos) -> {
                cells.add(pos.asLong());
                return cells.size() >= MAX_CELLS ? cells : null;
            }, cells -> cells);
            assertEquals(expected, walk(ox, oy, oz, ex, ey, ez), "from (%s, %s, %s) to (%s, %s, %s)".formatted(ox, oy, oz, ex, ey, ez));
        }
    }

    private static List<Long> walk(double ox, double oy, double oz, double ex, double ey, double ez) {
        var cells = new ArrayList<Long>();
        var walk = new CellWalk();
        walk.start(ox, oy, oz, ex, ey, ez);
        do {
            cells.add(BlockPos.asLong(walk.x, walk.y, walk.z));
        } while (cells.size() < MAX_CELLS && walk.next());
        return cells;
    }
}
