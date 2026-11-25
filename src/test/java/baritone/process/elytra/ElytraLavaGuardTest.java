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
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ElytraLavaGuardTest {
    @Test
    public void boostThresholdMeasuresForwardSpeedWithoutCountingFallSpeed() {
        assertTrue(ElytraBehavior.belowBoostSpeed(new Vec3(0.95, -1.5, 0), 1.1));
        assertFalse(ElytraBehavior.belowBoostSpeed(new Vec3(1.11, 0, 0), 1.1));
    }

    @Test
    public void shallowLavaUsesTheActualSurfaceAndTheSweptClearance() {
        BlockPos lava = new BlockPos(0, 31, 0);
        AABB box = new AABB(0.2, 31.85, 0.2, 0.8, 32.45, 0.8);
        assertFalse(ElytraLavaGuard.crossesHeat(box, new Vec3(0, -0.1, 0),
                sweep -> ElytraLaunchPlanner.intersectsLavaVolume(sweep, lava, 0.2)));
        assertTrue(ElytraLavaGuard.crossesHeat(box, new Vec3(0, -0.35, 0),
                sweep -> ElytraLaunchPlanner.intersectsLavaVolume(sweep, lava, 0.2)));
    }

    @Test
    public void detectsLavaDuringDescentBeforeThePlayerTouchesIt() {
        var player = new AABB(-0.3, 45, -0.3, 0.3, 45.6, 0.3);
        var dive = new Vec3(0.3, -1.2, 0.8);
        assertTrue(ElytraLavaGuard.threatens(player, dive, new Rotation(0, 11), 0, box -> box.minY < 33));
        assertFalse(ElytraLavaGuard.threatens(player, dive, new Rotation(0, 11), 0, box -> false));
    }

    @Test
    public void givesASlowUnpoweredGlideTimeToAvoidLavaBelow() {
        var player = new AABB(-0.3, 45, -0.3, 0.3, 45.6, 0.3);
        var heading = new Rotation(0, 0);
        assertTrue(ElytraLavaGuard.threatens(player, new Vec3(0, 0, 0.38), heading, 0, box -> box.minY < 41));
        assertFalse(ElytraLavaGuard.threatens(player, new Vec3(0, 0, 1.5), heading, 0, box -> box.minY < 41));
    }

    @Test
    public void allowsDescentAboveLavaWhenTheSimulatedPathStaysClear() {
        var player = new AABB(-0.3, 45, -0.3, 0.3, 45.6, 0.3);
        var motion = new Vec3(0, -0.5, 1.5);
        assertFalse(ElytraLavaGuard.threatens(player, motion, new Rotation(0, 0), 0, box -> box.minY < 36));
        assertTrue(ElytraLavaGuard.threatens(player, motion, new Rotation(0, 0), 0, box -> box.minY < 42));
    }

    @Test
    public void distinguishesAClimbFromContinuingToDescendAtTheOldVelocity() {
        var player = new AABB(-0.3, 45, -0.3, 0.3, 45.6, 0.3);
        var motion = new Vec3(0, -0.42, 1.5);
        assertFalse(ElytraLavaGuard.threatens(player, motion, new Rotation(0, -38), 0, box -> box.minY < 41));
        assertTrue(ElytraLavaGuard.threatens(player, motion, new Rotation(0, 11), 0, box -> box.minY < 41));
    }

    @Test
    public void detectsWallOnlyWhenThePlannedBoostReachesIt() {
        var player = new AABB(-0.3, 45, -0.3, 0.3, 45.6, 0.3);
        var heading = new Rotation(0, 0);
        assertFalse(ElytraLavaGuard.threatensWall(player, Vec3.ZERO, heading, 0, box -> box.maxZ > 6));
        assertTrue(ElytraLavaGuard.threatensWall(player, Vec3.ZERO, heading, 10, box -> box.maxZ > 6));
    }

    @Test
    public void refusesTheEarlyReboostAfterWallDamageNearTheSameSpot() {
        assertFalse(ElytraBehavior.canBoostAfterWall(15, 0.03));
        assertFalse(ElytraBehavior.canBoostAfterWall(11, 16));
        assertTrue(ElytraBehavior.canBoostAfterWall(15, 16));
        assertTrue(ElytraBehavior.canBoostAfterWall(40, 0.03));
    }

    @Test
    public void detectsCollisionInNegativeDirectionButCanSteerOffAContact() {
        var player = new AABB(-0.3, 45, -0.3, 0.3, 45.6, 0.3);
        assertTrue(ElytraLavaGuard.threatensWall(player, new Vec3(0, 0, -1), new Rotation(180, 0),
                0, box -> box.minZ < -4));
        assertFalse(ElytraLavaGuard.threatensWall(player, new Vec3(0, 0, 0.5), new Rotation(0, 0),
                0, box -> box.minZ <= player.minZ));
    }

    @Test
    public void checksForShallowLavaBelowAFlightAndLavaAboveAClimb() {
        var player = new AABB(-0.3, 45, -0.3, 0.3, 45.6, 0.3);
        assertTrue(ElytraLavaGuard.threatens(player, new Vec3(0, 0, 1), new Rotation(0, 0),
                0, box -> box.minY < 44.5));
        assertTrue(ElytraLavaGuard.threatens(player, Vec3.ZERO, new Rotation(0, -38),
                18, box -> box.maxY > 46.5));
    }

    @Test
    public void turnsForMoreClearTicksWhileWaitingForANewRoute() {
        var heading = new Rotation(0, 0);
        var escape = ElytraLavaGuard.glideAside(heading, 1, candidate -> candidate.getYaw() == 55 ? 8 : 1);
        assertFalse(escape.clearsHorizon());
        assertEquals(55, escape.rotation().getYaw(), 0);
        assertNull(ElytraLavaGuard.glideAside(heading, 8, candidate -> 8));
    }

    @Test
    public void anticipatesLavaThatHasNotYetReachedTheFlightHeight() {
        var box = new AABB(0.2, 40, 0.2, 0.8, 40.6, 0.8);
        var uncovered = new FallingLava(128, pos -> pos.getY() == 96 && pos.getX() == 0
                ? FallingLava.Cell.LAVA : FallingLava.Cell.OPEN);
        var underRoof = new FallingLava(128, pos -> switch (pos.getY()) {
            case 96 -> FallingLava.Cell.LAVA;
            case 50 -> FallingLava.Cell.BLOCKED;
            default -> FallingLava.Cell.OPEN;
        });
        assertTrue(uncovered.threatens(box));
        assertFalse(uncovered.threatens(box.move(1, 0, 0)));
        assertFalse(underRoof.threatens(box));
    }
}
