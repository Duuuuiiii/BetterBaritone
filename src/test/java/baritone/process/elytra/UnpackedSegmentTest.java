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
import org.junit.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class UnpackedSegmentTest {
    private static final BetterBlockPos START = new BetterBlockPos(0, 64, 0);
    private static final BetterBlockPos REJOIN = new BetterBlockPos(4, 64, 1);
    private static final BetterBlockPos END = new BetterBlockPos(8, 65, 2);

    @Test
    public void doesNotSpliceAPartialRouteAcrossTheMissingSection() {
        assertThrows(PathCalculationException.class,
                () -> new UnpackedSegment(Stream.of(START), false).spliceAt(REJOIN, List.of(END), true));
        assertEquals(List.of(START, REJOIN, END),
                new UnpackedSegment(Stream.of(START, REJOIN), true).spliceAt(REJOIN, List.of(END), true).collect());
    }

    @Test
    public void rejectsAnExtensionThatEndsWhereItStarted() {
        assertThrows(PathCalculationException.class,
                () -> new UnpackedSegment(Stream.of(REJOIN), false).extendFrom(REJOIN, List.of(START, REJOIN)));
        assertEquals(List.of(START, REJOIN, END),
                new UnpackedSegment(Stream.of(REJOIN, END), false).extendFrom(REJOIN, List.of(START, REJOIN)).collect());
    }
}
