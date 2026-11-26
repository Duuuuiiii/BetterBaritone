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
import org.junit.Test;

import static org.junit.Assert.*;

public class LongRangeElytraRouteTest {
    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final BlockPos GOAL = new BlockPos(2000, 64, 900);
    private static final long ORIGIN = node(0, 0);
    private static final long GUIDE = node(60, 27);
    private static final long CONTINUATION = node(160, 72);
    private static final PathSegment BASE = new PathSegment(false, new long[]{ORIGIN, node(128, 0)});
    private static final PathSegment APPROACH = new PathSegment(true, new long[]{ORIGIN, GUIDE});

    @Test
    public void prefersTheConnectedDiagonalRoute() {
        assertTrue(LongRangeElytraRoute.shouldCompare(START, GOAL, BASE));
        var onward = new PathSegment(false, new long[]{GUIDE, CONTINUATION});
        var chosen = LongRangeElytraRoute.choose(START, GOAL, BASE, APPROACH, onward);
        assertArrayEquals(new long[]{ORIGIN, GUIDE, CONTINUATION}, chosen.packed);
        assertFalse(chosen.finished);
        assertFalse(LongRangeElytraRoute.shouldCompare(START, GOAL, chosen));
    }

    @Test
    public void retainsOriginalWhenTheAlternativeIsDisconnectedOrBacktracks() {
        assertSame(BASE, LongRangeElytraRoute.choose(START, GOAL, BASE, APPROACH, null));
        var disconnected = new PathSegment(false, new long[]{node(61, 28), CONTINUATION});
        var deadEnd = new PathSegment(false, new long[]{GUIDE, node(64, 29)});
        var loop = new PathSegment(false, new long[]{GUIDE, ORIGIN, CONTINUATION});
        for (var rejected : new PathSegment[]{disconnected, deadEnd, loop}) {
            assertSame(BASE, LongRangeElytraRoute.choose(START, GOAL, BASE, APPROACH, rejected));
        }
    }

    @Test
    public void triesTheFinerGridWhenTheCoarseSearchGetsNowhere() {
        var shortRoute = new PathSegment(false, new long[]{ORIGIN, node(8, 0)});
        var narrowPassage = new PathSegment(false, new long[]{ORIGIN, node(24, 12)});
        var worse = new PathSegment(false, new long[]{ORIGIN, node(-12, 0)});
        assertTrue(LongRangeElytraRoute.shouldTryFine(START, GOAL, null));
        assertTrue(LongRangeElytraRoute.shouldTryFine(START, GOAL, shortRoute));
        assertFalse(LongRangeElytraRoute.shouldTryFine(START, GOAL, BASE));
        assertSame(narrowPassage, LongRangeElytraRoute.chooseFine(START, GOAL, shortRoute, narrowPassage));
        assertSame(shortRoute, LongRangeElytraRoute.chooseFine(START, GOAL, shortRoute, worse));
        assertSame(shortRoute, LongRangeElytraRoute.chooseFine(START, GOAL, shortRoute, null));
    }

    private static long node(int x, int z) {
        return BetterBlockPos.serializeToLong(x, 64, z);
    }
}
