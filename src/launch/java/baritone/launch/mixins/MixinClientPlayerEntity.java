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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.SprintStateEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.Rotation;
import baritone.behavior.LookBehavior;
import baritone.utils.InputOverrideHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * @author Brady
 * @since 8/1/2018
 */
@Mixin(LocalPlayer.class)
public abstract class MixinClientPlayerEntity {
    @Shadow
    protected abstract boolean isControlledCamera();

    @Unique
    private static final MethodHandle MAY_FLY = baritone$resolveMayFly();

    @Unique
    private static MethodHandle baritone$resolveMayFly() {
        try {
            var lookup = MethodHandles.publicLookup();
            return lookup.findVirtual(LocalPlayer.class, "mayFly", MethodType.methodType(boolean.class));
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/player/AbstractClientPlayer.tick()V",
                    shift = At.Shift.AFTER
            )
    )
    private void onPreUpdate(CallbackInfo ci) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone != null && !baritone.getElytraProcess().isActive()) {
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
        }
    }

    @Inject(
            method = "tick",
            at = @At(value = "INVOKE", target = "net/minecraft/client/player/AbstractClientPlayer.tick()V")
    )
    private void updateElytraBeforeMovement(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(player);
        if (baritone == null || !baritone.getElytraProcess().isActive()) {
            return;
        }
        // Tweakeroo can swap in a dummy input at tick HEAD when the camera is detached.
        if (Minecraft.getInstance().getCameraEntity() != player) {
            ((InputOverrideHandler) baritone.getInputOverrideHandler()).restoreMovementInput();
        }
        baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
    }

    @Redirect(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z"))
    private boolean sendFlightPositionWithDetachedCamera(LocalPlayer player) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(player);
        return (baritone != null && baritone.getElytraProcess().isActive()
                && Minecraft.getInstance().getCameraEntity() != player) || this.isControlledCamera();
    }

    // Vanilla applyInput samples both angles for first-person hand interpolation.
    @Redirect(method = "applyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
    private float handPitch(LocalPlayer player) {
        Rotation camera = baritone$flightCameraRotation(player);
        return camera == null ? player.getXRot() : camera.getPitch();
    }

    @Redirect(method = "applyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private float handYaw(LocalPlayer player) {
        Rotation camera = baritone$flightCameraRotation(player);
        return camera == null ? player.getYRot() : camera.getYaw();
    }

    @Unique
    private Rotation baritone$flightCameraRotation(LocalPlayer player) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(player);
        return baritone == null ? null : ((LookBehavior) baritone.getLookBehavior()).getFlightCameraRotation();
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "FIELD",
                    target = "net/minecraft/world/entity/player/Abilities.mayfly:Z"
            )
    )
    @Group(name = "mayFly", min = 1, max = 1)
    private boolean isAllowFlying(Abilities capabilities) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone == null) {
            return capabilities.mayfly;
        }
        return !baritone.getPathingBehavior().isPathing() && capabilities.mayfly;
    }

    @Redirect(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;mayFly()Z"
        )
    )
    @Group(name = "mayFly", min = 1, max = 1)
    private boolean onMayFlyNeoforge(LocalPlayer instance) throws Throwable {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone == null) {
            return (boolean) MAY_FLY.invokeExact(instance);
        }
        return !baritone.getPathingBehavior().isPathing() && (boolean) MAY_FLY.invokeExact(instance);
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Input;sprint()Z"
            )
    )
    private boolean redirectSprintInput(final Input instance) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone == null) {
            return instance.sprint();
        }
        SprintStateEvent event = new SprintStateEvent();
        baritone.getGameEventHandler().onPlayerSprintState(event);
        if (event.getState() != null) {
            return event.getState();
        }
        if (baritone != BaritoneAPI.getProvider().getPrimaryBaritone()) {
            // hitting control shouldn't make all bots sprint
            return false;
        }
        return instance.sprint();
    }

    @Inject(
            method = "rideTick",
            at = @At(
                    value = "HEAD"
            )
    )
    private void updateRidden(CallbackInfo cb) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone != null) {
            ((LookBehavior) baritone.getLookBehavior()).pig();
        }
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;tryToStartFallFlying()Z"
            )
    )
    private boolean tryToStartFallFlying(final LocalPlayer instance) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(instance);
        if (baritone != null && baritone.getPathingBehavior().isPathing()) {
            return false;
        }
        return instance.tryToStartFallFlying();
    }
}
