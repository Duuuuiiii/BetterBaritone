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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

final class ElytraLavaGuard {
    private static final int LOOKAHEAD = 18;
    private static final int DESCENT_LOOKAHEAD = 14;
    private static final int[] TURNS = {0, 25, -25, 55, -55, 90, -90, 135, -135, 180};
    private static final int[] CLIMB_ANGLES = {-24, -38, -52, -14};
    private static final int[] GLIDE_ANGLES = {-12, -5, 0, 5};

    private ElytraLavaGuard() {}

    static boolean threatens(LocalPlayer player, Rotation heading, int boostedTicks) {
        return threatens(player.getBoundingBox(), player.getDeltaMovement(), heading, boostedTicks,
                box -> touchesLava(player, box));
    }

    static boolean threatens(AABB box, Vec3 motion, Rotation heading, int boostedTicks, Predicate<AABB> lava) {
        Vec3 direction = look(heading);
        // Also check sustained downward motion if the next steering update does not slow the dive.
        if (motion.y < -0.35) {
            AABB descent = box;
            for (int tick = 0; tick < DESCENT_LOOKAHEAD; tick++) {
                if (lava.test(sweep(descent, motion))) {
                    return true;
                }
                descent = descent.move(motion);
            }
        }
        for (int tick = 0; tick < LOOKAHEAD; tick++) {
            motion = ElytraLaunchPlanner.glide(motion, direction, heading.getPitch());
            if (lava.test(sweep(box, motion))) {
                return true;
            }
            box = box.move(motion);
            if (tick < boostedTicks) {
                motion = ElytraLaunchPlanner.boost(motion, direction);
            }
        }
        return false;
    }

    static Rotation climb(LocalPlayer player, Rotation heading, int boostedTicks) {
        return findClearHeading(player, heading, boostedTicks, CLIMB_ANGLES, 0.25);
    }

    static Rotation glideAside(LocalPlayer player, Rotation heading, int boostedTicks) {
        return findClearHeading(player, heading, boostedTicks, GLIDE_ANGLES, Double.NEGATIVE_INFINITY);
    }

    private static Rotation findClearHeading(LocalPlayer player, Rotation heading, int boostedTicks,
                                             int[] pitches, double minimumRise) {
        for (int turn : TURNS) {
            for (int pitch : pitches) {
                Rotation candidate = new Rotation(heading.getYaw() + turn, pitch);
                if (canFly(player, candidate, boostedTicks, minimumRise)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    static boolean canClimb(LocalPlayer player, Rotation rotation, int boostedTicks) {
        return canFly(player, rotation, boostedTicks, 0.25);
    }

    private static boolean canFly(LocalPlayer player, Rotation rotation, int boostedTicks, double minimumRise) {
        Vec3 direction = look(rotation);
        Vec3 motion = player.getDeltaMovement();
        AABB box = player.getBoundingBox();
        double startingY = box.minY;
        boolean exitedLava = !player.isInLava();
        for (int tick = 0; tick < LOOKAHEAD; tick++) {
            motion = ElytraLaunchPlanner.glide(motion, direction, rotation.getPitch());
            AABB swept = sweep(box, motion);
            if (!solidClear(player, swept)) {
                return false;
            }
            if (touchesLava(player, swept)) {
                if (exitedLava || tick >= 8) {
                    return false;
                }
            } else {
                exitedLava = true;
            }
            box = box.move(motion);
            if (tick < boostedTicks) {
                motion = ElytraLaunchPlanner.boost(motion, direction);
            }
        }
        return exitedLava && box.minY > startingY + minimumRise;
    }

    private static Vec3 look(Rotation rotation) {
        return Vec3.directionFromRotation(rotation.getPitch(), rotation.getYaw());
    }

    private static AABB sweep(AABB box, Vec3 motion) {
        return box.expandTowards(motion.x, motion.y, motion.z).inflate(0.16, 0.08, 0.16);
    }

    private static boolean touchesLava(LocalPlayer player, AABB box) {
        var world = player.level();
        for (BlockPos pos : blocks(box)) {
            if (!world.isLoaded(pos)) {
                continue;
            }
            var fluid = world.getFluidState(pos);
            if (fluid.is(FluidTags.LAVA) && box.minY < pos.getY() + fluid.getHeight(world, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean solidClear(LocalPlayer player, AABB box) {
        var world = player.level();
        if (!world.noCollision(player, box)) {
            return false;
        }
        for (BlockPos pos : blocks(box)) {
            if (!world.isLoaded(pos) || world.getBlockState(pos).is(BlockTags.FIRE)) {
                return false;
            }
        }
        return true;
    }

    private static Iterable<BlockPos> blocks(AABB box) {
        return BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ));
    }
}
