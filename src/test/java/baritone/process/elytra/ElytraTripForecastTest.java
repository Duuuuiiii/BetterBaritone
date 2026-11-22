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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ElytraTripForecastTest {
    private static final long SECOND = 1_000_000_000L;
    private static final ElytraFlightStats.Estimate HISTORY = new ElytraFlightStats.Estimate(5_000, 500, 20);

    @Test
    public void measuresNetProgressAndRocketsSpentOnTheTrip() {
        var trip = new ElytraTripForecast();
        for (int t = 0; t <= 1200; t++) {
            if (t > 0 && t % 10 == 0) {
                trip.rocketUsed();
            }
            trip.sample(112_000 - t * 10, t * SECOND);
        }
        var forecast = trip.estimate(100_000, HISTORY);
        assertEquals(10_000, forecast.seconds());
        assertEquals(1_000, forecast.rockets());
        assertEquals(120, trip.rocketsUsed());

        var closer = trip.estimate(90_000, HISTORY);
        assertEquals(9_000, closer.seconds());
        assertEquals(900, closer.rockets());
    }

    @Test
    public void pausesAndLargePositionJumpsDoNotBecomeFlightProgress() {
        var trip = new ElytraTripForecast();
        trip.sample(20_000, 0);
        trip.rocketUsed();
        trip.interrupt();
        trip.sample(15_000, 1_000 * SECOND);
        trip.sample(7_000, 1_001 * SECOND);
        assertEquals(HISTORY, trip.estimate(7_000, HISTORY));
        assertEquals(1, trip.rocketsUsed());

        for (int t = 1002; t <= 1100; t++) {
            trip.sample(7_000 - (t - 1001) * 10, t * SECOND);
        }
        assertTrue(trip.estimate(6_000, HISTORY).seconds() < HISTORY.seconds());
    }
}
