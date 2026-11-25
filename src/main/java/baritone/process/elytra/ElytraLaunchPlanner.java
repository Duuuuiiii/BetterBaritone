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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class ElytraLaunchPlanner {

    private static final ThreadLocal<Boolean> USING_DETACHED_FLIGHT_ROCKET = new ThreadLocal<>();
    private static final double IGNITION_HEIGHT = 1.16;
    private static final int IGNITION_HORIZON = 24;
    private static final int GLIDE_REQUEST_INTERVAL = 4;
    private static final int[] FALLBACK_TURNS = {0, -20, 20, -45, 45, -90, 90, 180};
    private static final float[] PITCH_OFFSETS = {0, -7, 7, -14, 14};

    private ElytraLaunchPlanner() {}

    public static BlockPos routeStart(Vec3 ground) {
        return BlockPos.containing(ground.add(0, IGNITION_HEIGHT, 0));
    }

    public static boolean hasHeadroom(LocalPlayer player, Vec3 ground) {
        AABB space = player.getDimensions(Pose.STANDING).makeBoundingBox(ground).expandTowards(0, 1.3, 0);
        return clear(player, space) && !hasFallingLava(player, space);
    }

    public static boolean hasFallingLava(LocalPlayer player, AABB space) {
        return new FallingLava(player.level()).threatens(space);
    }

    public static Rotation plan(LocalPlayer player, Vec3 ground, List<BetterBlockPos> path) {
        FallingLava fallingLava = new FallingLava(player.level());
        Vec3 start = ground.add(0, IGNITION_HEIGHT, 0);
        AABB body = player.getDimensions(Pose.FALL_FLYING).makeBoundingBox(start);
        Rotation current = new Rotation(player.getYRot(), player.getXRot());
        Rotation localHeading = null;
        for (int i = Math.min(path.size() - 1, 16); i >= 1; i--) {
            Vec3 target = Vec3.atCenterOf(path.get(i));
            double horizontalDistance = target.subtract(start).horizontalDistance();
            if (horizontalDistance < 1 || horizontalDistance > 42) {
                continue;
            }
            Rotation toward = RotationUtils.calcRotationFromVec3d(start, target, current);
            localHeading = toward;
            if (horizontalDistance < 4) {
                continue;
            }
            for (float offset : PITCH_OFFSETS) {
                Rotation angle = toward.withPitch(Math.clamp(toward.getPitch() + offset, -22, 13));
                Vec3 direction = Vec3.directionFromRotation(angle.getPitch(), angle.getYaw());
                if (clear(player, body.expandTowards(direction.x * 2, direction.y * 2, direction.z * 2))
                        && safeIgnition(player, body, new Vec3(0, -0.08, 0), angle, fallingLava)) {
                    return angle;
                }
            }
        }
        return localHeading == null ? null : localHeading(body, localHeading.getYaw(),
                box -> clear(player, box) && !fallingLava.threatens(box));
    }

    public static Rotation planLocal(LocalPlayer player, Vec3 ground, BlockPos destination) {
        FallingLava fallingLava = new FallingLava(player.level());
        AABB overhead = player.getDimensions(Pose.STANDING).makeBoundingBox(ground).expandTowards(0, 1.3, 0);
        if (!clear(player, overhead) || fallingLava.threatens(overhead)) {
            return null;
        }
        Vec3 start = ground.add(0, IGNITION_HEIGHT, 0);
        AABB body = player.getDimensions(Pose.FALL_FLYING).makeBoundingBox(start);
        Rotation current = new Rotation(player.getYRot(), player.getXRot());
        Vec3 target = Vec3.atCenterOf(destination);
        float yaw = target.subtract(start).horizontalDistanceSqr() < 1
                ? current.getYaw() : RotationUtils.calcRotationFromVec3d(start, target, current).getYaw();
        return localHeading(body, yaw, box -> clear(player, box) && !fallingLava.threatens(box));
    }

    static Rotation localHeading(AABB body, float yaw, Predicate<AABB> clearance) {
        for (int turn : FALLBACK_TURNS) {
            for (int pitch : new int[]{-10, -20, 0}) {
                Rotation angle = new Rotation(yaw + turn, pitch);
                if (clearBoost(body, new Vec3(0, -0.08, 0), angle, clearance)) {
                    return angle;
                }
            }
        }
        return null;
    }

    public static boolean shouldRequestGlide(int tick, int previous, double verticalSpeed) {
        return verticalSpeed < 0 && (previous == 0 || tick - previous >= GLIDE_REQUEST_INTERVAL);
    }

    public static boolean canIgnite(LocalPlayer player, Rotation planned, Rotation actual) {
        return Math.abs(Mth.wrapDegrees(actual.getYaw() - planned.getYaw())) <= 7
                && Math.abs(actual.getPitch() - planned.getPitch()) <= 7
                && safeIgnition(player, player.getBoundingBox(), player.getDeltaMovement(), actual, new FallingLava(player.level()));
    }

    static boolean canBoost(LocalPlayer player, Rotation actual, FallingLava fallingLava) {
        return clearCruiseBoost(player.getBoundingBox(), player.getDeltaMovement(), actual,
                box -> !player.level().getBlockCollisions(player, box).iterator().hasNext(),
                box -> clearHeat(player, box) && !fallingLava.threatens(box));
    }

    static InteractionResult useRocketWithAim(LocalPlayer player, Rotation rotation, Supplier<InteractionResult> action) {
        if (Minecraft.getInstance().getCameraEntity() == player) {
            return useWithAim(player, rotation, action);
        }
        USING_DETACHED_FLIGHT_ROCKET.set(true);
        try {
            return useWithAim(player, rotation, action);
        } finally {
            USING_DETACHED_FLIGHT_ROCKET.remove();
        }
    }

    public static boolean isUsingDetachedFlightRocket() {
        return Boolean.TRUE.equals(USING_DETACHED_FLIGHT_ROCKET.get());
    }

    public static InteractionResult useWithAim(LocalPlayer player, Rotation rotation, Supplier<InteractionResult> action) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        player.setYRot(rotation.getYaw());
        player.setXRot(rotation.getPitch());
        try {
            return action.get();
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }

    private static boolean safeIgnition(LocalPlayer player, AABB body, Vec3 motion, Rotation angle,
                                        FallingLava fallingLava) {
        return clearBoost(body, motion, angle, box -> clear(player, box) && !fallingLava.threatens(box));
    }

    static boolean clearBoost(AABB body, Vec3 motion, Rotation angle, Predicate<AABB> clearance) {
        return clearBoost(body, motion, angle, (box, step) -> clearBoostStep(box, step, clearance));
    }

    static boolean clearCruiseBoost(AABB body, Vec3 motion, Rotation angle, Predicate<AABB> clearSolids,
                                    Predicate<AABB> clearHeat) {
        return clearBoost(body, motion, angle, (box, step) -> clearSweep(box.inflate(0.02), step, clearSolids)
                && clearBoostStep(box, step, clearHeat));
    }

    private static boolean clearBoost(AABB body, Vec3 motion, Rotation angle, BiPredicate<AABB, Vec3> clearStep) {
        return clearBoost(body, motion, angle, 0, clearStep)
                && clearBoost(body, motion, angle, 1, clearStep)
                && clearBoost(body, motion, angle, 2, clearStep);
    }

    private static boolean clearBoost(AABB body, Vec3 motion, Rotation angle, int boostDelay,
                                      BiPredicate<AABB, Vec3> clearStep) {
        Vec3 look = Vec3.directionFromRotation(angle.getPitch(), angle.getYaw());
        for (int tick = 0; tick < IGNITION_HORIZON; tick++) {
            motion = glide(motion, look, angle.getPitch());
            if (!clearStep.test(body, motion)) {
                return false;
            }
            body = body.move(motion);
            if (tick >= boostDelay) {
                motion = boost(motion, look);
            }
        }
        return true;
    }

    static boolean clearBoostStep(AABB body, Vec3 motion, Predicate<AABB> clearance) {
        return clearSweep(body.inflate(0.16, 0.10, 0.16), motion, clearance);
    }

    static boolean clearSweep(AABB padded, Vec3 motion, Predicate<AABB> clearance) {
        if (clearance.test(padded.expandTowards(motion.x, motion.y, motion.z))) {
            return true;
        }
        // The full bounding box includes untouched corners on diagonal moves.
        // Retest overlapping sections of the same padded trajectory.
        int movingAxes = (Math.abs(motion.x) > 0.05 ? 1 : 0) + (Math.abs(motion.y) > 0.05 ? 1 : 0)
                + (Math.abs(motion.z) > 0.05 ? 1 : 0);
        if (movingAxes < 2) {
            return false;
        }
        Vec3 step = motion.scale(0.25);
        for (int i = 0; i < 4; i++) {
            if (!clearance.test(padded.move(step.scale(i)).expandTowards(step.x, step.y, step.z))) {
                return false;
            }
        }
        return true;
    }

    private static boolean clear(LocalPlayer player, AABB box) {
        return player.level().noCollision(player, box) && clearHeat(player, box);
    }

    private static boolean clearHeat(LocalPlayer player, AABB box) {
        var world = player.level();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (!world.isLoaded(pos) || intersectsHeat(world, pos, box)) {
                return false;
            }
        }
        return true;
    }

    static boolean intersectsHeat(Level world, BlockPos pos, AABB box) {
        var state = world.getBlockState(pos);
        var fluid = state.getFluidState();
        return isHot(state) || fluid.is(FluidTags.LAVA) && intersectsLavaVolume(box, pos, fluid.getHeight(world, pos));
    }

    static boolean intersectsLavaVolume(AABB box, BlockPos pos, double height) {
        return box.intersects(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + height, pos.getZ() + 1);
    }

    public static boolean isHot(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.MAGMA_BLOCK) || CampfireBlock.isLitCampfire(state);
    }

    static Vec3 boost(Vec3 motion, Vec3 look) {
        return motion.add(look.scale(0.1).add(look.scale(1.5).subtract(motion).scale(0.5)));
    }

    static Vec3 glide(final Vec3 motion, final Vec3 lookDirection, final float pitch) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;

        float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
        double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double thisIsAlwaysOne = lookDirection.length();
        float pitchBase3 = Mth.cos(pitchRadians);
        pitchBase3 = (float) ((double) pitchBase3 * (double) pitchBase3 * Math.min(1, thisIsAlwaysOne / 0.4));
        motionY += -0.08 + (double) pitchBase3 * 0.06;
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * (double) pitchBase3;
            motionY += speedModifier;
            motionX += lookDirection.x * speedModifier / pitchBase2;
            motionZ += lookDirection.z * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0) { // if you are looking down (below level)
            double anotherSpeedModifier = flatMotion * (double) (-Mth.sin(pitchRadians)) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
            motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) { // this is always true unless you are looking literally straight up (let's just say the bot will never do that)
            motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.99f;
        motionY *= 0.98f;
        motionZ *= 0.99f;

        return new Vec3(motionX, motionY, motionZ);
    }

}
