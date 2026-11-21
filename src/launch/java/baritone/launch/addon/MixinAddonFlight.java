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

package baritone.launch.addon;

import baritone.api.BaritoneAPI;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.process.ElytraProcess;
import baritone.process.elytra.AddonFlightState;
import baritone.process.elytra.ElytraFlightStats;
import baritone.process.elytra.ElytraMileage;
import baritone.process.elytra.ElytraPanelFlight;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ElytraProcess.class, remap = false)
public abstract class MixinAddonFlight implements ElytraPanelFlight {
    @Shadow public ElytraProcess.State state;
    @Unique private AddonFlightState betterBaritone$flight;

    @Unique private AddonFlightState betterBaritone$state() {
        if (betterBaritone$flight == null) {
            betterBaritone$flight = new AddonFlightState((IElytraProcess) (Object) this);
        }
        return betterBaritone$flight;
    }

    @Inject(method = "pathTo(Lnet/minecraft/class_2338;)V", at = @At("RETURN"))
    private void betterBaritone$setDestination(BlockPos position, CallbackInfo callback) {
        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess() == (Object) this) {
            betterBaritone$state().start(position);
        }
    }

    @Inject(method = "onLostControl(Z)V", at = @At("HEAD"))
    private void betterBaritone$end(boolean destroyNative, CallbackInfo callback) {
        if (destroyNative && betterBaritone$flight != null) {
            betterBaritone$flight.end();
        }
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void betterBaritone$tick(boolean calcFailed, boolean safe, CallbackInfoReturnable<PathingCommand> callback) {
        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess() != (Object) this) {
            return;
        }
        PathingCommand command = betterBaritone$state().tick(state == ElytraProcess.State.LANDING);
        if (command != null) {
            callback.setReturnValue(command);
        }
    }

    @Override public boolean isFlightPaused() { return betterBaritone$state().isPaused(); }
    @Override public void toggleFlightPause() { betterBaritone$state().togglePause(); }
    @Override public void takeOver() { betterBaritone$state().takeOver(); }
    @Override public void requestLanding() { betterBaritone$state().takeOver(); }
    @Override public BlockPos flightDestination() { return betterBaritone$state().destination(); }
    @Override public double remainingDistance() { return betterBaritone$state().remainingDistance(); }
    @Override public double distanceToArrival(BlockPos target, boolean horizontal) { return betterBaritone$state().distanceTo(target); }
    @Override public String flightStatus() { return betterBaritone$state().status(); }
    @Override public ElytraFlightStats.Estimate flightEstimate() { return betterBaritone$state().estimateFlight(); }
    @Override public ElytraFlightStats.Estimate estimateForDistance(double distance) { return betterBaritone$state().estimate(distance); }
    @Override public ElytraMileage mileage() { return betterBaritone$state().mileage(); }
}
