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

import net.minecraft.world.phys.Vec3;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ElytraTripForecastTest {
    @Rule public TemporaryFolder profileDirectory = new TemporaryFolder();

    private static final long SECOND = 1_000_000_000L;
    private static final ElytraFlightStats.Estimate HISTORY = new ElytraFlightStats.Estimate(5_000, 500, 20);

    @Test
    public void measuresNetProgressAndRocketsSpentOnTheTrip() {
        var trip = new ElytraTripForecast();
        for (int t = 0; t <= 1200; t++) {
            if (t > 0 && t % 10 == 0) {
                trip.rocketUsed();
            }
            if (t > 0 && t % 50 == 0) {
                trip.recordWear(1);
            }
            trip.sample(112_000 - t * 10, t * SECOND);
        }
        var forecast = trip.estimate(100_000, HISTORY);
        assertEquals(10_000, forecast.seconds());
        assertEquals(1_000, forecast.rockets());
        assertEquals(200, forecast.durability());
        assertEquals(24, trip.wearUsed());
        assertEquals(120, trip.rocketsUsed());

        var closer = trip.estimate(90_000, HISTORY);
        assertEquals(9_000, closer.seconds());
        assertEquals(900, closer.rockets());
        assertEquals(180, closer.durability());
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

    @Test
    public void keepsTheFullDistanceWindowEvenWhenItTakesOverHalfAnHour() {
        var trip = new ElytraTripForecast();
        for (int t = 0; t <= 2700; t++) {
            trip.sample(110_800 - t * 4, t * SECOND);
        }
        assertEquals(25_000, trip.estimate(100_000, HISTORY).seconds());
    }

    @Test
    public void includesActiveTimeSpentStalledBetweenSamples() {
        var trip = new ElytraTripForecast();
        for (int t = 0; t <= 1_200; t++) {
            long stalled = t >= 600 ? 300 : 0;
            trip.sample(112_000 - t * 10, (t + stalled) * SECOND);
        }
        assertEquals(13_000, trip.estimate(100_000, HISTORY).seconds(), 30);
    }

    @Test
    public void savesCalibratedPaceForTheNextDeparture() {
        var profile = new ElytraMileage();
        profile.append(10_000, 400);
        assertEquals(5_000, profile.estimateSeconds(100_000));
        var trip = new ElytraTripForecast();
        for (int t = 0; t <= 1_300; t++) {
            if (t > 0 && t % 10 == 0) {
                trip.rocketUsed();
            }
            if (t > 0 && t % 25 == 0) {
                trip.recordWear(1);
            }
            trip.sample(109_600 - t * 8, t * SECOND);
            if (t == 1_200) {
                assertEquals(0, trip.calibratedSecondsPerTenThousand(), 0);
            }
        }
        profile.recordTripRates(trip);
        var path = profileDirectory.getRoot().toPath().resolve("mileage.properties");
        profile.save(path);
        assertEquals(new ElytraFlightStats.Estimate(12_500, 1_250, 500), ElytraMileage.load(path).estimate(100_000));
    }

    @Test
    public void onlyCountsSmallInventoryDurabilityLossAsWear() {
        var profile = new ElytraMileage();
        assertEquals(0, profile.sample(Vec3.ZERO, 470, 0));
        assertEquals(0, profile.sample(new Vec3(1, 0, 0), 470, SECOND));
        assertEquals(1, profile.sample(new Vec3(2, 0, 0), 469, 2 * SECOND));
        assertEquals(0, profile.sample(new Vec3(3, 0, 0), 500, 3 * SECOND));
        assertEquals(0, profile.sample(new Vec3(4, 0, 0), 200, 4 * SECOND));
        assertEquals(1, profile.sample(new Vec3(5, 0, 0), 199, 5 * SECOND));
        assertEquals(2, profile.summary().wear());
    }
}
