/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

final class ElytraSurveyPlan {
    private static final int WORLD_LIMIT = 29_999_983;
    private static final int CELL = 32;
    private final Config config;
    private final List<Point> candidates = new ArrayList<>();
    private int spiralIndex;
    private final Random random = new Random();

    ElytraSurveyPlan(Config config) {
        this.config = config;
        int rings = config.radius / config.spacing;
        int x = 0;
        int z = 0;
        int direction = config.direction;
        int[] dx = {0, 1, 0, -1};
        int[] dz = {-1, 0, 1, 0};
        // Two runs of each length trace an outward square spiral.
        for (int length = 1; length <= 2 * rings + 1; length++) {
            for (int side = 0; side < 2; side++) {
                for (int step = 0; step < length; step++) {
                    if (Math.abs(x) <= rings && Math.abs(z) <= rings) {
                        var point = new Point(config.center.x + x * config.spacing, config.center.z + z * config.spacing);
                        if (Math.abs(point.x) <= WORLD_LIMIT && Math.abs(point.z) <= WORLD_LIMIT) {
                            candidates.add(point);
                        }
                    }
                    x += dx[direction];
                    z += dz[direction];
                }
                direction = (direction + (config.clockwise ? 1 : 3)) % 4;
            }
        }
        if (config.random) {
            Collections.shuffle(candidates, random);
        }
    }

    Point next(Point from, Predicate<Point> available) {
        if (!config.random) {
            while (spiralIndex < candidates.size()) {
                Point candidate = candidates.get(spiralIndex++);
                if (from.distance(candidate) >= config.switchDistance + 16 && available.test(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int start = random.nextInt(candidates.size());
        Point best = null;
        int bestCoverage = 0;
        int checked = 0;
        for (int i = 0; i < candidates.size() && checked < 12; i++) {
            Point candidate = candidates.get((start + i) % candidates.size());
            double distance = from.distance(candidate);
            if (distance >= config.minLeg && distance <= config.maxLeg && available.test(candidate)) {
                checked++;
                int coverage = newCoverage(from, candidate, available);
                if (coverage == 3) {
                    return candidate;
                }
                if (best == null || coverage > bestCoverage) {
                    best = candidate;
                    bestCoverage = coverage;
                }
            }
        }
        return best;
    }

    private static int newCoverage(Point from, Point to, Predicate<Point> available) {
        int count = 0;
        for (int quarter = 1; quarter <= 3; quarter++) {
            var point = new Point(from.x + (to.x - from.x) * quarter / 4,
                    from.z + (to.z - from.z) * quarter / 4);
            if (available.test(point)) {
                count++;
            }
        }
        return count;
    }

    record Point(int x, int z) {
        double distance(Point other) {
            return Math.hypot((double) other.x - x, (double) other.z - z);
        }

        long cell() {
            return ((long) Math.floorDiv(x, CELL) << 32) | (Math.floorDiv(z, CELL) & 0xffffffffL);
        }
    }

    record Config(Point center, int radius, int spacing, int limit, int switchDistance,
                  boolean random, int minLeg, int maxLeg, int direction, boolean clockwise) {
        boolean valid() {
            return Math.abs((long) center.x) <= WORLD_LIMIT && Math.abs((long) center.z) <= WORLD_LIMIT
                    && spacing >= 160 && spacing <= 16_384 && radius >= spacing && radius <= 1_000_000
                    && radius / spacing <= 128 && limit >= 0 && limit <= 100_000
                    && switchDistance >= 52 && switchDistance <= spacing / 2
                    && (!random || minLeg >= switchDistance + 16 && maxLeg >= minLeg && maxLeg <= 2_000_000)
                    && direction >= 0 && direction < 4;
        }
    }
}
