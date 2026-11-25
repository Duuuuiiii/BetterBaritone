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

import baritone.api.utils.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ElytraLaunchPlannerTest {
    private static final AABB GLIDING_BOX = new AABB(-0.3, 10, -0.3, 0.3, 10.6, 0.3);
    private static final Vec3 FALLING = new Vec3(0, -0.08, 0);

    @Test
    public void blocksIgnitionWhenWallIsBeyondTheOldSixBlockCheck() {
        assertFalse(ElytraLaunchPlanner.clearBoost(GLIDING_BOX, FALLING, new Rotation(0, -10), box -> box.maxZ < 27));
    }

    @Test
    public void checksNegativeAxisAndAcceptsClearAir() {
        assertFalse(ElytraLaunchPlanner.clearBoost(GLIDING_BOX, FALLING, new Rotation(180, -10), box -> box.minZ > -27));
        assertTrue(ElytraLaunchPlanner.clearBoost(GLIDING_BOX, FALLING, new Rotation(0, -10), box -> true));
    }

    @Test
    public void picksAnOpenHeadingWhenTheRouteFacesAWall() {
        Rotation escape = ElytraLaunchPlanner.localHeading(GLIDING_BOX, 0, box -> box.maxZ < 7);
        assertNotNull(escape);
        assertTrue(ElytraLaunchPlanner.clearBoost(GLIDING_BOX, FALLING, escape, box -> box.maxZ < 7));
        assertNull(ElytraLaunchPlanner.localHeading(GLIDING_BOX, 0,
                box -> box.maxZ < 7 && box.minZ > -7 && box.maxX < 7 && box.minX > -7));
    }

    @Test
    public void checksIgnitionDuringHighSpeedDescentNearAWall() {
        var velocity = new Vec3(0, -0.45, 1.94);
        assertFalse(ElytraLaunchPlanner.clearBoost(GLIDING_BOX, velocity, new Rotation(0, 0.7f),
                box -> box.maxZ < 4));
        assertTrue(ElytraLaunchPlanner.clearBoost(GLIDING_BOX, velocity, new Rotation(0, 0.7f),
                box -> true));
    }

    @Test
    public void diagonalIgnitionChecksTheSweptTrajectoryInsteadOfEmptyCorners() {
        Vec3 diagonal = new Vec3(2, 0, 2);
        AABB corner = new AABB(1.85, 10, -0.1, 2.1, 11, 0.15);
        AABB onTrack = new AABB(0.9, 10, 0.9, 1.1, 11, 1.1);
        assertTrue(ElytraLaunchPlanner.clearBoostStep(GLIDING_BOX, diagonal, box -> !box.intersects(corner)));
        assertFalse(ElytraLaunchPlanner.clearBoostStep(GLIDING_BOX, diagonal, box -> !box.intersects(onTrack)));
    }

    @Test
    public void cruiseBoostFitsNarrowCorridorsWithoutRelaxingHeatClearance() {
        Rotation straight = new Rotation(0, 0);
        Vec3 motion = new Vec3(0, 0, 0.9);
        assertFalse(ElytraLaunchPlanner.clearBoost(GLIDING_BOX, motion, straight, box -> box.maxX < 0.38));
        assertTrue(ElytraLaunchPlanner.clearCruiseBoost(GLIDING_BOX, motion, straight,
                box -> box.maxX < 0.38, box -> true));
        assertFalse(ElytraLaunchPlanner.clearCruiseBoost(GLIDING_BOX, motion, straight,
                box -> box.maxX < 0.29, box -> true));
        assertFalse(ElytraLaunchPlanner.clearCruiseBoost(GLIDING_BOX, motion, straight,
                box -> true, box -> box.maxX < 0.38));
    }

    @Test
    public void retriesGlideOnlyAfterTheFirstRequestHadTimeToApply() {
        assertFalse(ElytraLaunchPlanner.shouldRequestGlide(5, 0, 0.1));
        assertTrue(ElytraLaunchPlanner.shouldRequestGlide(5, 0, -0.03));
        assertFalse(ElytraLaunchPlanner.shouldRequestGlide(7, 5, -0.1));
        assertTrue(ElytraLaunchPlanner.shouldRequestGlide(9, 5, -0.1));
    }
}
