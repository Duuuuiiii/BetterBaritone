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

import baritone.api.utils.BetterBlockPos;
import dev.babbaj.pathfinder.PathSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashSet;

final class LongRangeElytraRoute {
    private static final int COMPARE_DISTANCE = 96;

    private LongRangeElytraRoute() {}

    static boolean shouldTryFine(BlockPos start, BlockPos goal, PathSegment coarse) {
        return coarse == null || !coarse.finished
                && horizontalDistance(start.getCenter(), goal.getCenter()) >= 48
                && progress(start, goal, coarse) < 16;
    }

    static PathSegment chooseFine(BlockPos start, BlockPos goal, PathSegment coarse, PathSegment fine) {
        if (fine == null || fine.packed.length < 2) {
            return coarse;
        }
        if (coarse == null || fine.finished || progress(start, goal, fine) >= progress(start, goal, coarse) + 8) {
            return fine;
        }
        return coarse;
    }

    private static double progress(BlockPos start, BlockPos goal, PathSegment segment) {
        if (segment.packed.length == 0) {
            return 0;
        }
        Vec3 target = goal.getCenter();
        Vec3 end = BetterBlockPos.deserializeFromLong(segment.packed[segment.packed.length - 1]).getCenter();
        return horizontalDistance(start.getCenter(), target) - horizontalDistance(end, target);
    }

    static boolean shouldCompare(BlockPos start, BlockPos goal, PathSegment original) {
        return !original.finished && horizontalDistance(start.getCenter(), goal.getCenter()) >= 256
                && efficiency(start, goal, original.packed) < 0.97;
    }

    static PathSegment choose(BlockPos start, BlockPos goal, PathSegment original,
                              PathSegment approach, PathSegment onward) {
        int end = approach.packed.length - 1;
        if (!approach.finished || end < 1 || onward == null || onward.packed.length < 2
                || approach.packed[end] != onward.packed[0]) {
            return original;
        }
        var join = BetterBlockPos.deserializeFromLong(approach.packed[end]);
        var finish = BetterBlockPos.deserializeFromLong(onward.packed[onward.packed.length - 1]);
        Vec3 target = goal.getCenter();
        if (horizontalDistance(join.getCenter(), target) - horizontalDistance(finish.getCenter(), target) < 24
                || efficiency(join, goal, onward.packed) < 0.45) {
            return original;
        }
        long[] combined = Arrays.copyOf(approach.packed, end + onward.packed.length);
        System.arraycopy(onward.packed, 1, combined, end + 1, onward.packed.length - 1);
        var seen = new HashSet<Long>();
        for (long node : combined) {
            if (!seen.add(node)) {
                return original;
            }
        }
        return efficiency(start, goal, combined) >= efficiency(start, goal, original.packed) + 0.03
                ? new PathSegment(onward.finished, combined) : original;
    }

    private static double efficiency(BlockPos start, BlockPos goal, long[] path) {
        Vec3 current = start.getCenter();
        Vec3 target = goal.getCenter();
        double initialDistance = horizontalDistance(current, target);
        double length = 0;
        for (long node : path) {
            Vec3 next = BetterBlockPos.deserializeFromLong(node).getCenter();
            double step = current.distanceTo(next);
            if (length + step >= COMPARE_DISTANCE) {
                current = current.lerp(next, (COMPARE_DISTANCE - length) / step);
                length = COMPARE_DISTANCE;
                break;
            }
            length += step;
            current = next;
        }
        return length > 0 ? (initialDistance - horizontalDistance(current, target)) / length : 0;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
}
