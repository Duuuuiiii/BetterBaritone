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
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ElytraFlightStatsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void formatsRocketEstimateInShulkerBoxesAndStacks() {
        assertEquals("飞行数据不足", ElytraFlightScreen.rocketAmount(-1, false));
        assertEquals("0 个", ElytraFlightScreen.rocketAmount(0, false));
        assertEquals("63 个", ElytraFlightScreen.rocketAmount(63, false));
        assertEquals("1 组", ElytraFlightScreen.rocketAmount(64, false));
        assertEquals("1 盒", ElytraFlightScreen.rocketAmount(1728, false));
        assertEquals("1 盒 2 组 12 个", ElytraFlightScreen.rocketAmount(1868, false));
    }

    @Test
    public void estimateUsesNetProgressAndConsumption() {
        ElytraFlightStats stats = new ElytraFlightStats();
        stats.tick(0, 1_000, 400);
        stats.recordRocket();
        stats.tick(TimeUnit.SECONDS.toNanos(5), 1_020, 397);
        stats.recordRocket();
        stats.tick(TimeUnit.SECONDS.toNanos(10), 900, 395);

        ElytraFlightStats.Estimate estimate = stats.estimate(900);
        assertEquals(90, estimate.seconds());
        assertEquals(18, estimate.rockets());
        assertEquals(45, estimate.durability());
    }

    @Test
    public void hidesEstimateWithoutProgressAndIgnoresArmorSwaps() {
        ElytraFlightStats stats = new ElytraFlightStats();
        stats.tick(0, 1_000, 100);
        stats.tick(TimeUnit.SECONDS.toNanos(10), 1_010, 430);
        assertNull(stats.estimate(1_010));

        stats.tick(TimeUnit.SECONDS.toNanos(20), 900, 320);
        ElytraFlightStats.Estimate estimate = stats.estimate(900);
        assertEquals(180, estimate.seconds());
        assertEquals(-1, estimate.rockets());
        assertEquals(-1, estimate.durability());
    }

    @Test
    public void discardsOldSamples() {
        ElytraFlightStats stats = new ElytraFlightStats();
        stats.tick(0, 10_000, 300);
        stats.recordRocket();
        stats.tick(TimeUnit.SECONDS.toNanos(60), 9_900, 299);
        stats.recordRocket();
        stats.tick(TimeUnit.SECONDS.toNanos(70), 9_800, 298);

        ElytraFlightStats.Estimate estimate = stats.estimate(9_800);
        assertEquals(980, estimate.seconds());
        assertEquals(98, estimate.rockets());
        assertEquals(98, estimate.durability());
    }
    @Test
    public void learnsFromDistinctWindowsAndPersistsRates() throws Exception {
        ElytraFlightStats stats = new ElytraFlightStats();
        stats.tick(0, 4_000, 350);
        stats.recordRocket();
        stats.tick(TimeUnit.SECONDS.toNanos(30), 3_500, 347);
        assertNull(stats.takeProfileSample(false));
        stats.recordRocket();
        stats.tick(TimeUnit.SECONDS.toNanos(60), 3_000, 344);
        ElytraFlightProfile profile = stats.takeProfileSample(false);
        assertEquals(new ElytraFlightProfile(1_000, 2, 6), profile);
        assertNull(stats.takeProfileSample(true));
        assertEquals(new ElytraFlightStats.Estimate(180, 6, 18), profile.estimate(3_000));

        var path = folder.getRoot().toPath().resolve("profiles").resolve("test-player.properties");
        assertTrue(profile.save(path));
        assertEquals(profile, ElytraFlightProfile.load(path));
        ElytraFlightProfile updated = profile.learn(new ElytraFlightProfile(800, 7, 11));
        assertTrue(updated.save(path));
        ElytraFlightProfile restored = ElytraFlightProfile.load(path);
        assertEquals(updated, restored);
        assertEquals(960, restored.blocksPerMinute(), 1e-9);
        assertEquals(3, restored.rocketsPerThousand(), 1e-9);
        assertEquals(7, restored.durabilityPerThousand(), 1e-9);
    }
    @Test
    public void etaDoesNotJumpOnTransientStall() {
        ElytraFlightStats stats = new ElytraFlightStats();
        assertEquals(3600, stats.smoothEta(0, 3600, 3600));
        long afterStall = stats.smoothEta(TimeUnit.SECONDS.toNanos(1), 200_000, 3600);
        assertTrue(afterStall >= 3599 && afterStall < 3620);
        assertEquals(afterStall, stats.smoothEta(TimeUnit.MILLISECONDS.toNanos(1500), 200, 3600));
    }
    @Test
    public void mileageUsesExactlyTheLastTenThousandBlocksAndSurvivesRestart() {
        ElytraMileage mileage = new ElytraMileage();
        mileage.append(4_000, 200);
        mileage.append(5_000, 300);
        assertEquals(-1, mileage.recentSecondsPerTenThousand(), 0);
        mileage.rocketUsed();
        mileage.append(2_000, 200);
        assertEquals(650, mileage.recentSecondsPerTenThousand(), 0.0001);
        var file = folder.getRoot().toPath().resolve("flight-profiles").resolve("player.properties");
        mileage.save(file);
        ElytraMileage restored = ElytraMileage.load(file);
        assertEquals(mileage.summary(), restored.summary());
        assertEquals(650, restored.recentSecondsPerTenThousand(), 0.0001);
    }
    @Test
    public void detectsAnOrbitEvenWhenSpeedStaysHigh() {
        ElytraOrbitTracker loop = new ElytraOrbitTracker();
        ElytraOrbitTracker cruise = new ElytraOrbitTracker();
        boolean detected = false;
        for (int i = 0; i <= 32; i++) {
            double angle = i * Math.PI / 16;
            detected |= loop.sample(i * 10, new net.minecraft.world.phys.Vec3(27 * Math.cos(angle), 64, 27 * Math.sin(angle)));
            assertTrue(!cruise.sample(i * 10, new net.minecraft.world.phys.Vec3(i * 5, 64, 0)));
        }
        assertTrue(detected);
    }
    @Test
    public void rotatesFlightDiagnosticsWithoutLosingRecentEvents() throws Exception {
        var path = folder.getRoot().toPath().resolve("logs").resolve("flight.log");
        ElytraFlightDiagnostics.append(path, "one", 1);
        ElytraFlightDiagnostics.append(path, "two", 1);
        ElytraFlightDiagnostics.append(path, "three", 1);
        assertEquals("three", java.nio.file.Files.readString(path));
        assertEquals("two", java.nio.file.Files.readString(path.resolveSibling("flight.1.log")));
        assertEquals("one", java.nio.file.Files.readString(path.resolveSibling("flight.2.log")));
    }
}
