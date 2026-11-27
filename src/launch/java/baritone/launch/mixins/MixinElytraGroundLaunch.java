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

package baritone.launch.mixins;

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.process.elytra.ElytraLaunchPlanner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

@Mixin(value = ElytraLaunchPlanner.class, remap = false)
public abstract class MixinElytraGroundLaunch {

    @Shadow
    private static boolean clear(LocalPlayer player, AABB box) {
        throw new AssertionError();
    }

    @Shadow
    static boolean clearBoost(AABB body, Vec3 motion, Rotation angle, Predicate<AABB> clearance) {
        throw new AssertionError();
    }

    @Inject(method = "plan", at = @At("RETURN"), cancellable = true)
    private static void findLocalLaunch(LocalPlayer player, Vec3 ground, List<BetterBlockPos> path,
                                        CallbackInfoReturnable<Rotation> result) {
        if (result.getReturnValue() != null || !ElytraLaunchPlanner.hasHeadroom(player, ground)) {
            return;
        }

        Vec3 start = ground.add(0, 1.16, 0);
        AABB body = player.getDimensions(Pose.FALL_FLYING).makeBoundingBox(start);
        Rotation view = new Rotation(player.getYRot(), player.getXRot());
        float yaw = view.getYaw();
        for (BetterBlockPos pos : path) {
            Vec3 target = pos.getCenter();
            double distance = target.subtract(start).horizontalDistance();
            if (distance >= 1 && distance <= 42) {
                yaw = RotationUtils.calcRotationFromVec3d(start, target, view).getYaw();
                break;
            }
        }
        Vec3 motion = new Vec3(0, -0.08, 0);
        Predicate<AABB> clearance = box -> clear(player, box);
        for (int turn : new int[]{0, -30, 30, -60, 60, -90, 90, 180}) {
            for (int pitch : new int[]{-10, -20, 0}) {
                Rotation candidate = new Rotation(yaw + turn, pitch);
                if (clearBoost(body, motion, candidate, clearance)) {
                    result.setReturnValue(candidate);
                    return;
                }
            }
        }
    }
}
