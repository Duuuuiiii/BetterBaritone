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
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

final class ElytraLavaGuard {
    private static final int LOOKAHEAD = 18;
    private static final int SLOW_GLIDE_LOOKAHEAD = 36;
    private static final int DESCENT_LOOKAHEAD = 24;
    private static final int INERTIA_LOOKAHEAD = 5;
    private static final int WALL_LOOKAHEAD = 8;
    private static final int BOOSTED_WALL_LOOKAHEAD = 12;
    private static final int DESCEND_BEFORE_LEVELING = 8;
    private static final float LEVELING_PITCH = -16f;
    private static final int[] TURNS = {0, 25, -25, 55, -55, 90, -90, 135, -135, 180};
    private static final int[] CLIMB_ANGLES = {-24, -38, -52, -14};

    private ElytraLavaGuard() {}

    static boolean threatens(LocalPlayer player, Rotation heading, int boostedTicks, FallingLava fallingLava) {
        return threatens(player.getBoundingBox(), player.getDeltaMovement(), heading, boostedTicks,
                box -> fallingLava.threatens(box) || touchesHeat(player, box));
    }

    static boolean runningOutOfClearance(LocalPlayer player, Rotation heading, FallingLava fallingLava) {
        return runningOutOfClearance(player.getBoundingBox(), player.getDeltaMovement(), heading,
                box -> fallingLava.threatens(box) || touchesHeat(player, box));
    }

    static boolean runningOutOfClearance(AABB box, Vec3 motion, Rotation heading, Predicate<AABB> heat) {
        // After several failed ignitions, leave vertical room to turn or accept a delayed rocket.
        return threatens(box.expandTowards(0, -1.5, 0), motion, heading, 0, heat);
    }

    static boolean canDescendAndLevel(LocalPlayer player, Rotation heading, int boostTicks, FallingLava fallingLava) {
        return canDescendAndLevel(player.getBoundingBox(), player.getDeltaMovement(), heading, boostTicks,
                box -> fallingLava.threatens(box) || touchesHeat(player, box), box -> !solidClear(player, box));
    }

    static boolean canDescendAndLevel(AABB box, Vec3 motion, Rotation heading, int boostTicks,
                                     Predicate<AABB> heat, Predicate<AABB> solid) {
        Vec3 descending = look(heading);
        Vec3 leveling = Vec3.directionFromRotation(LEVELING_PITCH, heading.getYaw());
        for (int tick = 0; tick < DESCENT_LOOKAHEAD; tick++) {
            boolean descend = tick < DESCEND_BEFORE_LEVELING;
            Vec3 direction = descend ? descending : leveling;
            motion = ElytraLaunchPlanner.glide(motion, direction, descend ? heading.getPitch() : LEVELING_PITCH);
            if (crossesHeat(box, motion, heat) || !clearsSolids(box, motion, solid.negate())) {
                return false;
            }
            box = box.move(motion);
            if (tick < boostTicks) {
                motion = ElytraLaunchPlanner.boost(motion, direction);
            }
        }
        return motion.y >= -0.02;
    }

    static boolean threatens(AABB box, Vec3 motion, Rotation heading, int boostedTicks, Predicate<AABB> lava) {
        Vec3 direction = look(heading);
        // Leave room for a few ticks of existing inertia before the new aim takes effect.
        if (motion.y < -0.35) {
            AABB descent = box;
            for (int tick = 0; tick < INERTIA_LOOKAHEAD; tick++) {
                if (crossesHeat(descent, motion, lava)) {
                    return true;
                }
                descent = descent.move(motion);
            }
        }
        int horizon = motion.y < -0.35 ? DESCENT_LOOKAHEAD : LOOKAHEAD;
        if (boostedTicks == 0 && motion.horizontalDistanceSqr() < 0.9 * 0.9 && motion.y <= 0.05) {
            horizon = SLOW_GLIDE_LOOKAHEAD;
        }
        for (int tick = 0; tick < horizon; tick++) {
            motion = ElytraLaunchPlanner.glide(motion, direction, heading.getPitch());
            if (crossesHeat(box, motion, lava)) {
                return true;
            }
            box = box.move(motion);
            if (tick < boostedTicks) {
                motion = ElytraLaunchPlanner.boost(motion, direction);
            }
        }
        return false;
    }

    static boolean threatensWallSoon(LocalPlayer player, Rotation heading, int boostedTicks) {
        return threatensWall(player.getBoundingBox(), player.getDeltaMovement(), heading, boostedTicks,
                box -> !chunksLoaded(player, box) || hitsSolid(player, box));
    }

    static boolean threatensWall(AABB box, Vec3 motion, Rotation heading, int boostedTicks,
                                 Predicate<AABB> solid) {
        int horizon = boostedTicks > 0 ? BOOSTED_WALL_LOOKAHEAD : WALL_LOOKAHEAD;
        Vec3 direction = look(heading);
        for (int tick = 0; tick < horizon; tick++) {
            motion = ElytraLaunchPlanner.glide(motion, direction, heading.getPitch());
            if (!clearsSolids(box, motion, solid.negate())) {
                return true;
            }
            box = box.move(motion);
            if (tick < boostedTicks) {
                motion = ElytraLaunchPlanner.boost(motion, direction);
            }
        }
        return false;
    }

    static Rotation climb(LocalPlayer player, Rotation heading, int boostedTicks,
                          UnaryOperator<Rotation> actualAim, FallingLava fallingLava) {
        for (int turn : TURNS) {
            for (int pitch : CLIMB_ANGLES) {
                Rotation candidate = new Rotation(heading.getYaw() + turn, pitch);
                Rotation actual = actualAim.apply(candidate);
                if (clearTicks(player, actual, boostedTicks, 0, 0.25, fallingLava) == LOOKAHEAD
                        && canIgniteEscape(player, actual, boostedTicks, fallingLava)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean canIgniteEscape(LocalPlayer player, Rotation heading, int boostTicks, FallingLava fallingLava) {
        if (!player.isInLava()) {
            return ElytraLaunchPlanner.canBoost(player, heading, fallingLava);
        }
        return clearTicks(player, heading, boostTicks, 1, 0.25, fallingLava) == LOOKAHEAD
                && clearTicks(player, heading, boostTicks, 2, 0.25, fallingLava) == LOOKAHEAD;
    }

    static boolean canContinueEscape(LocalPlayer player, Rotation heading, int boostTicks, FallingLava fallingLava) {
        return clearTicks(player, heading, boostTicks, Double.NEGATIVE_INFINITY, fallingLava) == LOOKAHEAD
                && (player.isInLava() || !threatens(player, heading, boostTicks, fallingLava));
    }

    static Escape glideAside(LocalPlayer player, Rotation heading, Rotation current, int boostedTicks,
                             UnaryOperator<Rotation> actualAim, FallingLava fallingLava, Vec3 towardGoal) {
        int currentClear = clearTicks(player, current, boostedTicks, Double.NEGATIVE_INFINITY, fallingLava);
        return glideAside(heading, currentClear, towardGoal, candidate -> {
            Rotation actual = actualAim.apply(candidate);
            int safeTicks = clearTicks(player, actual, boostedTicks, Double.NEGATIVE_INFINITY, fallingLava);
            return safeTicks == LOOKAHEAD && !player.isInLava() && threatens(player, actual, boostedTicks, fallingLava)
                    ? LOOKAHEAD - 1 : safeTicks;
        });
    }

    static Escape glideAside(Rotation heading, int currentlyClearTicks, Vec3 towardGoal, ToIntFunction<Rotation> clearance) {
        int bestTicks = currentlyClearTicks;
        Rotation best = null;
        Escape reverse = null;
        float pitchAhead = Math.clamp(heading.getPitch(), -24f, 24f);
        float[] pitches = {pitchAhead * 0.75f, pitchAhead * 0.5f, 0, -12, LEVELING_PITCH, -24, -38, -52};
        for (int turn : TURNS) {
            for (float pitch : pitches) {
                Rotation candidate = new Rotation(heading.getYaw() + turn, pitch);
                boolean away = headsAway(candidate, towardGoal);
                if (away && reverse != null) {
                    continue;
                }
                int ticks = clearance.applyAsInt(candidate);
                if (ticks == LOOKAHEAD) {
                    if (!away) {
                        return new Escape(candidate, true);
                    }
                    reverse = new Escape(candidate, true);
                }
                if (ticks > bestTicks) {
                    bestTicks = ticks;
                    best = candidate;
                }
            }
        }
        if (reverse != null) {
            return reverse;
        }
        return best == null ? null : new Escape(best, false);
    }

    static boolean headsAway(Rotation heading, Vec3 towardGoal) {
        return Vec3.directionFromRotation(0, heading.getYaw()).dot(towardGoal) < -0.1;
    }

    record Escape(Rotation rotation, boolean clearsHorizon) {}

    private static int clearTicks(LocalPlayer player, Rotation rotation, int boostedTicks, double minimumRise,
                                  FallingLava fallingLava) {
        return clearTicks(player, rotation, boostedTicks, 0, minimumRise, fallingLava);
    }

    private static int clearTicks(LocalPlayer player, Rotation rotation, int boostedTicks, int boostDelay,
                                  double minimumRise, FallingLava fallingLava) {
        Vec3 direction = look(rotation);
        Vec3 motion = player.getDeltaMovement();
        AABB box = player.getBoundingBox();
        double startingY = box.minY;
        boolean exitedLava = !player.isInLava();
        boolean exitedFallingLava = !fallingLava.threatens(box);
        for (int tick = 0; tick < LOOKAHEAD; tick++) {
            motion = ElytraLaunchPlanner.glide(motion, direction, rotation.getPitch());
            if (!clearsSolids(box, motion, candidate -> solidClear(player, candidate))) {
                return tick;
            }
            if (crossesHeat(box, motion, fallingLava::threatens)) {
                // If the source appeared directly overhead, allow a short unboosted escape from its column.
                if (exitedFallingLava || tick >= 6) {
                    return tick;
                }
            } else {
                exitedFallingLava = true;
            }
            if (crossesHeat(box, motion, candidate -> touchesHeat(player, candidate))) {
                if (exitedLava || tick >= 8 + boostDelay) {
                    return tick;
                }
            } else {
                exitedLava = true;
            }
            box = box.move(motion);
            if (tick >= boostDelay && tick - boostDelay < boostedTicks) {
                motion = ElytraLaunchPlanner.boost(motion, direction);
            }
        }
        return exitedLava && exitedFallingLava && box.minY > startingY + minimumRise ? LOOKAHEAD : 0;
    }

    private static Vec3 look(Rotation rotation) {
        return Vec3.directionFromRotation(rotation.getPitch(), rotation.getYaw());
    }

    static boolean crossesHeat(AABB box, Vec3 motion, Predicate<AABB> heat) {
        AABB padded = box.expandTowards(0, -0.35, 0).inflate(0.16, 0.08, 0.16);
        return !ElytraLaunchPlanner.clearSweep(padded, motion, heat.negate());
    }

    private static boolean touchesHeat(LocalPlayer player, AABB box) {
        var world = player.level();
        for (BlockPos pos : blocks(box)) {
            if (world.isLoaded(pos) && ElytraLaunchPlanner.intersectsHeat(world, pos, box)) {
                return true;
            }
        }
        return false;
    }

    private static boolean clearsSolids(AABB box, Vec3 motion, Predicate<AABB> clearance) {
        // Ignore a rounding error at a wall the player is already touching.
        return ElytraLaunchPlanner.clearSweep(box.deflate(1.0E-5), motion, clearance);
    }

    private static boolean hitsSolid(LocalPlayer player, AABB box) {
        return player.level().getBlockCollisions(player, box).iterator().hasNext();
    }

    private static boolean chunksLoaded(LocalPlayer player, AABB box) {
        var chunks = player.level().getChunkSource();
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++) {
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++) {
                if (!chunks.hasChunk(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean solidClear(LocalPlayer player, AABB box) {
        var world = player.level();
        if (!chunksLoaded(player, box) || hitsSolid(player, box)) {
            return false;
        }
        for (BlockPos pos : blocks(box)) {
            if (world.getBlockState(pos).is(BlockTags.FIRE)) {
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
