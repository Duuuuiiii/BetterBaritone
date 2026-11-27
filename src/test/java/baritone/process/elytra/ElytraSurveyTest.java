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

import baritone.process.elytra.ElytraSurveyPlan.Config;
import baritone.process.elytra.ElytraSurveyPlan.Point;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.HashSet;

import static org.junit.Assert.*;

public class ElytraSurveyTest {
    private static final Point ORIGIN = new Point(0, 0);

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void spiralVisitsEachGridPointOnceInEitherDirection() {
        for (boolean clockwise : new boolean[]{true, false}) {
            var plan = new ElytraSurveyPlan(config(false, clockwise));
            var visited = new HashSet<Point>();
            visited.add(ORIGIN);
            Point first = plan.next(ORIGIN, p -> !visited.contains(p));
            assertEquals(new Point(192, 0), first);
            visited.add(first);
            Point second = plan.next(first, p -> !visited.contains(p));
            assertEquals(new Point(192, clockwise ? 192 : -192), second);
            visited.add(second);
            Point from = second;
            Point next;
            while ((next = plan.next(from, p -> !visited.contains(p))) != null) {
                assertTrue(visited.add(next));
                from = next;
            }
            assertEquals(9, visited.size());
        }
    }

    @Test
    public void randomRespectsRangeAndSkipsVisited() {
        var plan = new ElytraSurveyPlan(config(true, true));
        var visited = new HashSet<Point>();
        for (int i = 0; i < 4; i++) {
            Point next = plan.next(ORIGIN, p -> !visited.contains(p));
            assertNotNull(next);
            assertEquals(192, next.distance(ORIGIN), 0);
            assertTrue(visited.add(next));
        }
        assertNull(plan.next(ORIGIN, p -> !visited.contains(p)));
    }

    @Test
    public void savesCoverageAndSettingsAndIgnoresATruncatedRecord() throws Exception {
        var file = folder.getRoot().toPath().resolve("survey.log");
        var history = new ElytraSurveyHistory(file);
        history.mark(new Point(-1, -1));
        history.mark(new Point(0, 0));
        history.flush();
        history.saveConfig(config(true, false));
        history.saveConfig(config(false, true));
        Files.writeString(file, Long.toString(new Point(96, 96).cell()), java.nio.file.StandardOpenOption.APPEND);

        var loaded = new ElytraSurveyHistory(file);
        assertTrue(loaded.contains(new Point(-32, -32)));
        assertTrue(loaded.contains(new Point(31, 31)));
        assertFalse(loaded.contains(new Point(96, 96)));
        assertEquals(config(false, true), ElytraSurveyHistory.loadConfig(file));
        loaded.mark(new Point(128, 128));
        loaded.flush();
        assertTrue(new ElytraSurveyHistory(file).contains(new Point(128, 128)));
    }

    private static Config config(boolean random, boolean clockwise) {
        return new Config(ORIGIN, 192, 192, 0, 56, random, 180, 200, 1, clockwise);
    }
}
