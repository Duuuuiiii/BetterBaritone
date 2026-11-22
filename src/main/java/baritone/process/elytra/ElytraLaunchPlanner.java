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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class ElytraLaunchPlanner {

    private static final double IGNITION_HEIGHT = 1.16;
    private static final int IGNITION_HORIZON = 24;
    private static final float[] PITCH_OFFSETS = {0, -7, 7, -14, 14};

    private ElytraLaunchPlanner() {}

    public static BlockPos routeStart(Vec3 ground) {
        return BlockPos.containing(ground.add(0, IGNITION_HEIGHT, 0));
    }

    public static boolean hasHeadroom(LocalPlayer player, Vec3 ground) {
        return clear(player, player.getDimensions(Pose.STANDING).makeBoundingBox(ground).expandTowards(0, 1.3, 0));
    }

    public static Rotation plan(LocalPlayer player, Vec3 ground, List<BetterBlockPos> path) {
        Vec3 start = ground.add(0, IGNITION_HEIGHT, 0);
        AABB body = player.getDimensions(Pose.FALL_FLYING).makeBoundingBox(start);
        Rotation current = new Rotation(player.getYRot(), player.getXRot());
        for (int i = Math.min(path.size() - 1, 16); i >= 1; i--) {
            Vec3 target = Vec3.atCenterOf(path.get(i));
            double horizontalDistance = target.subtract(start).horizontalDistance();
            if (horizontalDistance < 4 || horizontalDistance > 42) {
                continue;
            }
            Rotation toward = RotationUtils.calcRotationFromVec3d(start, target, current);
            for (float offset : PITCH_OFFSETS) {
                Rotation angle = toward.withPitch(Math.clamp(toward.getPitch() + offset, -22, 13));
                Vec3 direction = Vec3.directionFromRotation(angle.getPitch(), angle.getYaw());
                if (clear(player, body.expandTowards(direction.x * 2, direction.y * 2, direction.z * 2))
                        && safeIgnition(player, body, new Vec3(0, -0.08, 0), angle)) {
                    return angle;
                }
            }
        }
        return null;
    }

    public static boolean canIgnite(LocalPlayer player, Rotation planned, Rotation actual) {
        return Math.abs(Mth.wrapDegrees(actual.getYaw() - planned.getYaw())) <= 7
                && Math.abs(actual.getPitch() - planned.getPitch()) <= 7
                && safeIgnition(player, player.getBoundingBox(), player.getDeltaMovement(), actual);
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

    private static boolean safeIgnition(LocalPlayer player, AABB body, Vec3 motion, Rotation angle) {
        return clearBoost(body, motion, angle, box -> clear(player, box));
    }

    static boolean clearBoost(AABB body, Vec3 motion, Rotation angle, Predicate<AABB> clearance) {
        return clearBoost(body, motion, angle, 0, clearance) && clearBoost(body, motion, angle, 2, clearance);
    }

    private static boolean clearBoost(AABB body, Vec3 motion, Rotation angle, int boostDelay, Predicate<AABB> clearance) {
        Vec3 look = Vec3.directionFromRotation(angle.getPitch(), angle.getYaw());
        for (int tick = 0; tick < IGNITION_HORIZON; tick++) {
            motion = glide(motion, look, angle.getPitch());
            AABB swept = body.expandTowards(motion.x, motion.y, motion.z).inflate(0.16, 0.10, 0.16);
            if (!clearance.test(swept)) {
                return false;
            }
            body = body.move(motion);
            if (tick >= boostDelay) {
                motion = boost(motion, look);
            }
        }
        return true;
    }

    private static boolean clear(LocalPlayer player, AABB box) {
        var world = player.level();
        if (!world.noCollision(player, box)) {
            return false;
        }
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (!world.isLoaded(pos) || world.getFluidState(pos).is(FluidTags.LAVA)
                    || world.getBlockState(pos).is(BlockTags.FIRE)) {
                return false;
            }
        }
        return true;
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
