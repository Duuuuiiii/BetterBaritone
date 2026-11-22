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

package baritone.process;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.elytra.ElytraBehavior;
import baritone.process.elytra.ElytraFlightDiagnostics;
import baritone.process.elytra.ElytraFlightStats;
import baritone.process.elytra.ElytraLaunchPlanner;
import baritone.process.elytra.ElytraMileage;
import baritone.process.elytra.ElytraPanelFlight;
import baritone.process.elytra.ElytraSupplyAlerts;
import baritone.process.elytra.ElytraTripForecast;
import static baritone.process.elytra.ElytraUi.text;
import baritone.process.elytra.NetherPathfinderContext;
import baritone.process.elytra.NullElytraProcess;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends BaritoneProcessHelper implements IBaritoneProcess, IElytraProcess, AbstractGameEventListener, ElytraPanelFlight {
    public State state;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private boolean predictingTerrain;
    private BlockPos flightDestination;
    private String flightTraceId;
    private ElytraMileage mileage;
    private Path mileagePath;
    private UUID mileagePlayer;
    private long lastMileageSave;
    private ElytraTripForecast trip = new ElytraTripForecast();
    private boolean horizontalGoal;
    private BlockPos pausedDestination;
    private boolean pausedHorizontalGoal;
    private final ElytraSupplyAlerts supplyAlerts = new ElytraSupplyAlerts();
    private boolean landRequested;
    private boolean hasFlown;
    private boolean launchNeedsPath;
    private Rotation launchAim;
    private int launchTicks;
    private int glideRequestTick;
    private boolean launchIgnitionRejected;
    private Vec3 launchAnchor;
    private Vec3 launchRouteOrigin;
    private CompletableFuture<Void> launchRoute;
    private int nextLaunchAttempt;
    private int nextRouteRetry;
    private Vec3 escapeTarget;
    private int escapeTicks;
    private boolean collisionObserved;
    private int blockedFlightTicks;
    private final Deque<Vec3> escapeWaypoints = new ArrayDeque<>();

    @Override
    public void onLostControl() {
        traceFlight("flight-end", currentDestination());
        this.flightTraceId = null;
        finishMileage();
        this.flightDestination = null;
        this.pausedDestination = null;
        this.trip = new ElytraTripForecast();
        this.landRequested = false;
        this.hasFlown = false;
        this.launchNeedsPath = false;
        this.launchAim = null;
        this.launchIgnitionRejected = false;
        this.launchAnchor = null;
        this.launchRouteOrigin = null;
        this.launchRoute = null;
        this.nextLaunchAttempt = 0;
        this.nextRouteRetry = 0;
        this.escapeTarget = null;
        this.escapeWaypoints.clear();
        this.blockedFlightTicks = 0;
        this.collisionObserved = false;
        this.supplyAlerts.reset();
        this.badLandingSpots.clear();
        this.state = State.START_FLYING;
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.reachedGoal = false;
        this.goal = null;
        destroyBehaviorAsync();
    }

    private ElytraProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final Baritone baritone) {
        return NetherPathfinderContext.isSupported()
                ? new ElytraProcess(baritone)
                : new NullElytraProcess(baritone);
    }

    @Override
    public boolean isActive() {
        return this.behavior != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.flightDestination;
        if (destination != null) {
            restartFlight(destination, this.horizontalGoal);
            this.repackChunks();
        }
    }

    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a spot to jump off from. Consider starting from a higher location, near an overhang. Or, you can disable elytraAutoJump and just manually begin gliding.";

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        final long seedSetting = Baritone.settings().elytraNetherSeed.value;
        if (seedSetting != this.behavior.context.getSeed()) {
            logDirect("Nether seed changed, recalculating path");
            this.resetState();
        }
        if (predictingTerrain != Baritone.settings().elytraPredictTerrain.value) {
            logDirect("elytraPredictTerrain setting changed, recalculating path");
            predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
            this.resetState();
        }

        tickTelemetry();
        boolean groundedRecovery = ctx.player().onGround() && this.state != State.LANDING
                && (this.hasFlown || this.state == State.START_FLYING);
        this.behavior.onTick(this.launchAim == null && !groundedRecovery);
        if (ctx.player().isInLava() && !ctx.player().isFallFlying()) {
            this.launchAim = null;
            this.escapeTarget = null;
            this.escapeWaypoints.clear();
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            if (ctx.player().tickCount % 20 == 0) {
                traceFlight("lava-swim-up", currentDestination());
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (this.escapeTarget != null) {
            return tickGroundEscape();
        }
        if (this.launchAim != null) {
            return tickLaunch();
        }

        if (groundedRecovery) {
            baritone.getInputOverrideHandler().clearAllKeys();
            tryGroundLaunch();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (calcFailed) {
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        boolean safetyLanding = false;
        boolean airborneGlide = ctx.player().isFallFlying() && !ctx.player().onGround();
        if (airborneGlide && shouldLandForSafety()) {
            if (Baritone.settings().elytraAllowEmergencyLand.value) {
                logDirect("Emergency landing - almost out of elytra durability or fireworks");
                safetyLanding = true;
            } else {
                logDirect("almost out of elytra durability or fireworks, but I'm going to continue since elytraAllowEmergencyLand is false");
            }
        }
        final BetterBlockPos last = this.behavior.pathManager.path.getLast();
        boolean canFinish = canFinishFlight(remainingDistance(), safetyLanding, this.landRequested, this.goingToLandingSpot);
        boolean lowSpeedCollision = airborneGlide && ctx.player().horizontalCollision
                && ctx.player().getDeltaMovement().horizontalDistanceSqr() < 0.0625;
        this.blockedFlightTicks = lowSpeedCollision ? this.blockedFlightTicks + 1 : 0;
        boolean incompleteRoute = !canFinish && this.behavior.pathManager.isComplete()
                && (last == null || ctx.player().position().distanceToSqr(last.getCenter()) < 48 * 48);
        if (airborneGlide && !this.goingToLandingSpot && (this.blockedFlightTicks >= 12 || incompleteRoute)
                && ctx.player().tickCount >= this.nextRouteRetry && this.behavior.pathManager.retryDestination()) {
            this.nextRouteRetry = ctx.player().tickCount + 200;
            traceFlight(this.blockedFlightTicks >= 12 ? "wall-contact-reroute" : "incomplete-route-retry", currentDestination());
        }
        if (airborneGlide && canFinish && this.state != State.LANDING && (this.goingToLandingSpot
                || this.behavior.pathManager.isComplete() || safetyLanding || this.landRequested)) {
            if (last != null && (ctx.player().position().distanceToSqr(last.getCenter()) < (48 * 48) || safetyLanding || this.landRequested) && !goingToLandingSpot) {
                logDirect("Path complete, picking a nearby safe landing spot...");
                BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet());
                // if this fails we will just keep orbiting the last node until we run out of rockets or the user intervenes
                if (landingSpot != null) {
                    this.pathTo0(landingSpot, true);
                    this.landingSpot = landingSpot;
                }
                this.goingToLandingSpot = true;
                if (landingSpot != null) {
                    // The new behavior has not had its first tick yet.
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }

            if (last != null && ctx.player().position().distanceToSqr(last.getCenter()) < 1) {
                if (!safetyLanding && !this.landRequested && Baritone.settings().notificationOnPathComplete.value && !reachedGoal) {
                    logNotification("Pathing complete", false);
                }
                if (!safetyLanding && !this.landRequested && Baritone.settings().disconnectOnArrival.value && !reachedGoal) {
                    // don't be active when the user logs back in
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                reachedGoal = true;

                // we are goingToLandingSpot and we are in the last node of the path
                if (this.goingToLandingSpot) {
                    this.state = State.LANDING;
                    logDirect("Above the landing spot, landing...");
                }
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
            if (ctx.player().isFallFlying() && endPos != null) {
                Vec3 from = ctx.player().position();
                Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

                if (ctx.player().position().y < endPos.y - LANDING_COLUMN_HEIGHT) {
                    logDirect("bad landing spot, trying again...");
                    landingSpotIsBad(this.behavior, endPos);
                }
            }
        }

        if (airborneGlide) {
            this.hasFlown = true;
            startPathAfterLaunch();
            if (this.state != State.LANDING) {
                this.state = State.FLYING;
            }
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            behavior.tick();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (!ctx.player().onGround()) {
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect("Landed, but still moving, waiting for velocity to die down... ");
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Done :)");
            baritone.getInputOverrideHandler().clearAllKeys();
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            this.state = ctx.player().onGround() && Baritone.settings().elytraAutoJump.value
                    ? State.LOCATE_JUMP
                    : State.START_FLYING;
        }

        if (this.state == State.LOCATE_JUMP) {
            if (shouldLandForSafety()) {
                logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (this.goal == null) {
                this.goal = new GoalYLevel(31);
            }
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() == this.goal) {
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    ElytraBehavior current = this.behavior;
                    this.launchNeedsPath = false;
                    this.state = State.PAUSE;
                    current.pathManager.pathToDestination(from).whenComplete((result, ex) -> ctx.minecraft().execute(() -> {
                        if (this.behavior != current || this.state != State.PAUSE) {
                            return;
                        }
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                        } else {
                            onLostControl();
                        }
                    }));
                } else {
                    onLostControl();
                    logDirect(AUTO_JUMP_FAILURE_MSG);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(baritone));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            final boolean canStartFlying = ctx.player().getDeltaMovement().y < -0.377
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.state = State.START_FLYING;
            } else {
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            if (ctx.player().getDeltaMovement().y < -0.377) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    public void landingSpotIsBad(ElytraBehavior source, BetterBlockPos endPos) {
        if (this.behavior != source) {
            return;
        }
        traceFlight("landing-site-rejected", endPos);
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.state = State.FLYING;
        if (this.flightDestination != null && !source.destination.equals(this.flightDestination)) {
            pathTo0(this.flightDestination, false);
        }
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            this.behavior = null;
            Baritone.getExecutor().execute(behavior::destroy);
        }
    }

    @Override
    public double priority() {
        return 0; // higher priority than CustomGoalProcess
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        if (this.behavior != null) {
            this.behavior.repackChunks();
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : null;
    }

    @Override
    public void pathTo(BlockPos destination) {
        beginFlight(destination, false);
    }

    private void beginFlight(BlockPos destination, boolean horizontal) {
        if (ctx.player() == null || ctx.player().level().dimension() != Level.NETHER) {
            return;
        }
        onLostControl();
        this.flightDestination = destination;
        this.horizontalGoal = horizontal;
        this.flightTraceId = Long.toUnsignedString(System.nanoTime(), 36);
        traceFlight("flight-start", destination);
        this.launchNeedsPath = ctx.player().onGround() || Baritone.settings().elytraAutoJump.value && !ctx.player().isFallFlying();
        pathTo0(destination, false);
    }

    private void pathTo0(BlockPos destination, boolean appendDestination) {
        if (ctx.player() == null || ctx.player().level().dimension() != Level.NETHER) {
            return;
        }
        destroyBehaviorAsync();
        this.predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
        this.behavior = new ElytraBehavior(this.baritone, this, destination, appendDestination);
        if (ctx.world() != null) {
            this.behavior.repackChunks();
        }
        traceFlight(appendDestination ? "landing-site-selected" : "route-target", destination);
        if (appendDestination) {
            this.behavior.pathManager.pathToDestination();
        } else if (!ctx.player().onGround()) {
            this.behavior.pathTo();
        }
    }

    @Override
    public void pathTo(Goal iGoal) {
        final int x;
        final int y;
        final int z;
        if (iGoal instanceof GoalXZ) {
            GoalXZ goal = (GoalXZ) iGoal;
            x = goal.getX();
            y = 64;
            z = goal.getZ();
        } else if (iGoal instanceof GoalBlock) {
            GoalBlock goal = (GoalBlock) iGoal;
            x = goal.x;
            y = goal.y;
            z = goal.z;
        } else {
            throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
        }
        if (y <= 0 || y >= 128) {
            throw new IllegalArgumentException("The y of the goal is not between 0 and 128");
        }
        beginFlight(new BlockPos(x, y, z), iGoal instanceof GoalXZ);
    }

    public boolean isFlightPaused() {
        return this.pausedDestination != null;
    }

    public void toggleFlightPause() {
        if (isFlightPaused()) {
            BlockPos target = this.pausedDestination;
            boolean horizontal = this.pausedHorizontalGoal;
            restartFlight(target, horizontal);
            return;
        }
        if (isActive()) {
            BlockPos target = this.flightDestination;
            boolean horizontal = this.horizontalGoal;
            ElytraTripForecast activeTrip = this.trip;
            baritone.getPathingBehavior().forceCancel();
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getLookBehavior().clearTarget();
            this.pausedDestination = target;
            this.pausedHorizontalGoal = horizontal;
            this.trip = activeTrip;
            this.trip.interrupt();
        } else if (baritone.getPathingControlManager().mostRecentInControl().isPresent()) {
            baritone.getPathingBehavior().forceCancel();
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getLookBehavior().clearTarget();
        }
    }

    private void restartFlight(BlockPos target, boolean horizontal) {
        ElytraTripForecast activeTrip = this.trip;
        beginFlight(target, horizontal);
        this.trip = activeTrip;
        this.trip.interrupt();
    }

    public BlockPos flightDestination() {
        return isFlightPaused() ? this.pausedDestination : this.flightDestination;
    }

    public double remainingDistance() {
        BlockPos target = flightDestination();
        if (target == null || ctx.player() == null) {
            return 0;
        }
        return distanceToArrival(target, isFlightPaused() ? this.pausedHorizontalGoal : this.horizontalGoal);
    }

    public double distanceToArrival(BlockPos target, boolean horizontal) {
        Vec3 remaining = Vec3.atCenterOf(target).subtract(ctx.player().position());
        return horizontal ? remaining.horizontalDistance() : remaining.length();
    }

    public int equippedDurability() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        return chest.is(Items.ELYTRA) ? chest.getMaxDamage() - chest.getDamageValue() : -1;
    }

    public String flightStatus() {
        if (isFlightPaused()) {
            return text("手动控制", "Manual control");
        }
        if (!isActive()) {
            return text("待起飞", "Ready");
        }
        if (this.state == State.LANDING || this.goingToLandingSpot) {
            return text("正在降落", "Landing");
        }
        if (this.landRequested || this.reachedGoal) {
            return text("寻找落点", "Finding a landing site");
        }
        if (this.hasFlown && ctx.player().onGround() && this.launchAim == null) {
            return text("等待安全起飞空间", "Waiting for takeoff clearance");
        }
        return this.state.description;
    }

    public ElytraFlightStats.Estimate flightEstimate() {
        if (!isActive() || this.reachedGoal || this.landRequested) {
            return null;
        }
        double remaining = remainingDistance();
        return this.trip.estimate(remaining, estimateForDistance(remaining));
    }

    @Override
    public long flightRocketsUsed() {
        return this.trip.rocketsUsed();
    }

    public ElytraFlightStats.Estimate estimateForDistance(double distance) {
        ElytraMileage stats = mileage();
        ElytraMileage.Summary total = stats.summary();
        double seconds = stats.recentSecondsPerTenThousand();
        if (seconds <= 0) {
            seconds = total.blocks() >= 64 && total.seconds() > 0
                    ? ElytraMileage.ETA_BLOCKS * total.seconds() / total.blocks() : 1000;
        }
        double factor = distance / Math.max(1, total.blocks());
        long rockets = distance == 0 ? 0
                : total.blocks() >= 64 && total.rockets() > 0 ? (long) Math.ceil(total.rockets() * factor) : -1;
        return new ElytraFlightStats.Estimate(Math.round(seconds * distance / ElytraMileage.ETA_BLOCKS),
                rockets,
                distance == 0 ? 0 : total.blocks() >= 64 && total.wear() > 0 ? (long) Math.ceil(total.wear() * factor) : -1);
    }

    public ElytraMileage mileage() {
        UUID player = ctx.minecraft().getUser().getProfileId();
        if (!player.equals(this.mileagePlayer)) {
            finishMileage();
            this.mileagePlayer = player;
            this.mileagePath = baritone.getDirectory().resolve("flight-profiles")
                    .resolve(player + "-mileage.properties");
            this.mileage = ElytraMileage.load(this.mileagePath);
        }
        return this.mileage;
    }

    private void saveMileage() {
        if (this.mileage != null) {
            this.mileage.save(this.mileagePath);
            this.lastMileageSave = System.nanoTime();
        }
    }

    private void finishMileage() {
        if (this.mileage != null) {
            this.mileage.interrupt();
            saveMileage();
        }
    }

    public void takeOver() {
        if (isActive()) {
            baritone.getPathingBehavior().forceCancel();
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getLookBehavior().clearTarget();
            logDirect(text("已交还飞行控制", "Flight controls released"));
        }
    }

    public void requestLanding() {
        if (isActive() && ctx.player().isFallFlying()) {
            this.landRequested = true;
        } else {
            takeOver();
        }
    }

    public void traceFlight(String event, BlockPos controllerTarget) {
        traceFlight(event, controllerTarget, "");
    }

    private void traceFlight(String event, BlockPos controllerTarget, String detail) {
        if (this.flightTraceId == null || ctx.player() == null) {
            return;
        }
        Vec3 pos = ctx.player().position();
        Vec3 motion = ctx.player().getDeltaMovement();
        ElytraFlightDiagnostics.record(baritone.getDirectory(), String.format(Locale.ROOT,
                "flight=%s tick=%d event=%s state=%s arrived=%s landing=%s pos=%.2f,%.2f,%.2f"
                        + " speed=%.2f vertical=%.2f yaw=%.1f pitch=%.1f onGround=%s gliding=%s lava=%s"
                        + " collision=%s rockets=%d destination=%s steering=%s%s",
                this.flightTraceId, ctx.player().tickCount, event, this.state,
                this.reachedGoal, this.goingToLandingSpot, pos.x, pos.y, pos.z,
                motion.horizontalDistance() * 20, motion.y * 20, ctx.player().getYRot(), ctx.player().getXRot(),
                ctx.player().onGround(), ctx.player().isFallFlying(), ctx.player().isInLava(),
                ctx.player().horizontalCollision || ctx.player().verticalCollision,
                availableRockets(), this.flightDestination, controllerTarget, detail));
    }

    public int availableRockets() {
        int count = 0;
        for (ItemStack stack : ctx.player().getInventory().getNonEquipmentItems()) {
            if (ElytraBehavior.isFireworks(stack)) {
                count += stack.getCount();
            }
        }
        ItemStack offhand = ctx.player().getOffhandItem();
        return count + (ElytraBehavior.isFireworks(offhand) ? offhand.getCount() : 0);
    }

    public void onRocketUsed() {
        mileage().rocketUsed();
        this.trip.rocketUsed();
        traceFlight("rocket-confirmed", currentDestination());
    }

    private void tickTelemetry() {
        if (this.flightDestination != null && !this.goingToLandingSpot) {
            this.trip.sample(remainingDistance());
        }
        if (ctx.player().tickCount % 20 == 0) {
            this.supplyAlerts.update(ctx.player(), availableRockets(), flightEstimate(), this::logDirect);
        }
        this.supplyAlerts.tick(ctx.player());
        if (ctx.player().tickCount % 40 == 0) {
            traceFlight("sample", currentDestination());
        }
        if (ctx.player().tickCount % 1200 == 0 && !this.goingToLandingSpot) {
            ElytraFlightStats.Estimate estimate = flightEstimate();
            if (estimate != null) {
                traceFlight("forecast", currentDestination(), String.format(Locale.ROOT,
                        " remainingBlocks=%.0f etaSeconds=%d estimatedRockets=%d tripRockets=%d",
                        remainingDistance(), estimate.seconds(), estimate.rockets(), flightRocketsUsed()));
            }
        }
        boolean collision = ctx.player().isFallFlying() && ctx.player().horizontalCollision;
        if (collision && !this.collisionObserved) {
            traceFlight("collision-observed", currentDestination());
        }
        this.collisionObserved = collision;
        if (!ctx.player().isFallFlying() || ctx.player().onGround()) {
            if (this.mileage != null) {
                this.mileage.interrupt();
            }
            return;
        }
        ItemStack elytra = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        mileage().sample(ctx.player().position(), elytra.is(Items.ELYTRA) ? elytra.getDamageValue() : -1);
        if (System.nanoTime() - this.lastMileageSave >= 30_000_000_000L) {
            saveMileage();
        }
    }

    private void tryGroundLaunch() {
        Vec3 ground = ctx.player().position();
        if (ctx.player().tickCount < this.nextLaunchAttempt && this.launchAnchor != null
                && this.launchAnchor.distanceToSqr(ground) < 0.25) {
            return;
        }
        if (ctx.player().isFallFlying() || ctx.player().isInLava() || ctx.player().isInWater()
                || ctx.player().isOnFire() || shouldLandForSafety()) {
            return;
        }
        if (!ElytraLaunchPlanner.hasHeadroom(ctx.player(), ground)) {
            waitForGroundRetry(ground, "launch-no-clearance");
            return;
        }
        if (this.launchRoute == null || this.launchRouteOrigin.distanceToSqr(ground) >= 0.25) {
            if (!this.behavior.pathManager.isRecalculating()) {
                BlockPos start = ElytraLaunchPlanner.routeStart(ground);
                this.launchRouteOrigin = ground;
                this.launchRoute = this.behavior.pathManager.pathToDestination(start);
                traceFlight("launch-route-planning", start);
            }
            return;
        }
        if (!this.launchRoute.isDone()) {
            return;
        }
        if (this.launchRoute.isCompletedExceptionally()) {
            waitForGroundRetry(ground, "launch-route-unavailable");
            return;
        }
        this.launchAim = ElytraLaunchPlanner.plan(ctx.player(), ground, this.behavior.pathManager.getPath());
        this.launchRoute = null;
        this.launchRouteOrigin = null;
        if (this.launchAim == null) {
            waitForGroundRetry(ground, "launch-route-unsafe");
            return;
        }
        this.launchNeedsPath = false;
        this.launchAnchor = ground;
        this.launchTicks = 0;
        this.glideRequestTick = 0;
        this.launchIgnitionRejected = false;
        this.state = State.START_FLYING;
        traceFlight("launch-route-ready", currentDestination());
        traceFlight("launch-jump", currentDestination());
    }

    private void waitForGroundRetry(Vec3 ground, String event) {
        this.launchRoute = null;
        this.launchRouteOrigin = null;
        this.launchAnchor = ground;
        this.nextLaunchAttempt = ctx.player().tickCount + 40;
        traceFlight(event, currentDestination());
        findGroundEscape();
        this.escapeTarget = this.escapeWaypoints.pollFirst();
        if (this.escapeTarget != null) {
            this.escapeTicks = 0;
            traceFlight("ground-escape-start", BlockPos.containing(this.escapeTarget));
        } else {
            traceFlight("ground-escape-blocked", currentDestination());
        }
    }

    private AABB standingBox(Vec3 feet) {
        return ctx.player().getDimensions(Pose.STANDING).makeBoundingBox(feet);
    }

    private Rotation findLaunchAim(AABB body, Vec3 feet) {
        if (!launchSpaceClear(body.expandTowards(0, 1.3, 0))) {
            return null;
        }
        Vec3 toward = Vec3.atCenterOf(this.behavior.destination).subtract(feet);
        float yaw = toward.horizontalDistanceSqr() < 1
                ? ctx.player().getYRot() : RotationUtils.calcRotationFromVec3d(Vec3.ZERO, toward, ctx.playerRotations()).getYaw();
        for (int turn : new int[]{0, 45, -45, 90, -90, 135, -135, 180}) {
            float angle = yaw + turn;
            Vec3 forward = Vec3.directionFromRotation(-10, angle);
            boolean clear = true;
            for (int step = 0; step <= 12; step++) {
                double distance = step * 0.5;
                if (!launchSpaceClear(body.inflate(0.15, 0.1, 0.15)
                        .move(forward.x * distance, 0.85 + forward.y * distance, forward.z * distance))) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return new Rotation(angle, -10);
            }
        }
        return null;
    }

    private void findGroundEscape() {
        this.escapeWaypoints.clear();
        Vec3 start = ctx.player().position();
        AABB body = ctx.player().getBoundingBox();
        int radius = 10;
        int width = radius * 2 + 1;
        boolean[][] visited = new boolean[width][width];
        Deque<EscapeNode> frontier = new ArrayDeque<>();
        frontier.add(new EscapeNode(0, 0, null));
        visited[radius][radius] = true;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!frontier.isEmpty()) {
            EscapeNode node = frontier.removeFirst();
            Vec3 offset = new Vec3(node.x * 0.5, 0, node.z * 0.5);
            Vec3 feet = start.add(offset);
            if (offset.horizontalDistanceSqr() >= 0.5 && findLaunchAim(standingBox(feet), feet) != null) {
                for (EscapeNode current = node; current.previous != null; current = current.previous) {
                    this.escapeWaypoints.addFirst(start.add(current.x * 0.5, 0, current.z * 0.5));
                }
                return;
            }
            for (int[] step : steps) {
                int nextX = node.x + step[0];
                int nextZ = node.z + step[1];
                if (nextX * nextX + nextZ * nextZ > radius * radius || visited[nextX + radius][nextZ + radius]) {
                    continue;
                }
                Vec3 next = new Vec3(nextX * 0.5, 0, nextZ * 0.5);
                if (safeGroundStep(body.move(offset.lerp(next, 0.5))) && safeGroundStep(body.move(next))) {
                    visited[nextX + radius][nextZ + radius] = true;
                    frontier.addLast(new EscapeNode(nextX, nextZ, node));
                }
            }
        }
    }

    private record EscapeNode(int x, int z, EscapeNode previous) {}

    private boolean safeGroundStep(AABB box) {
        if (!launchSpaceClear(box) || !hazardFree(box.move(0, -0.15, 0))) {
            return false;
        }
        // Check both sides of the player's footprint, so the escape cannot walk off a ledge.
        for (double x : new double[]{box.minX + 0.08, box.maxX - 0.08}) {
            for (double z : new double[]{box.minZ + 0.08, box.maxZ - 0.08}) {
                if (ctx.world().noCollision(ctx.player(), new AABB(x - 0.02, box.minY - 0.15, z - 0.02,
                        x + 0.02, box.minY, z + 0.02))) {
                    return false;
                }
            }
        }
        return true;
    }

    private PathingCommand tickGroundEscape() {
        baritone.getInputOverrideHandler().clearAllKeys();
        Vec3 position = ctx.player().position();
        Vec3 delta = this.escapeTarget.subtract(position);
        boolean reached = delta.horizontalDistanceSqr() <= 0.045;
        if (reached && !this.escapeWaypoints.isEmpty()) {
            this.escapeTarget = this.escapeWaypoints.removeFirst();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Vec3 direction = new Vec3(delta.x, 0, delta.z).normalize();
        double step = Math.min(0.30, delta.horizontalDistance());
        if (reached || ++this.escapeTicks > 80 || !ctx.player().onGround() || ctx.player().isOnFire()
                || Math.abs(delta.y) > 0.2 || !safeGroundStep(ctx.player().getBoundingBox().move(direction.scale(step)))) {
            traceFlight(reached ? "ground-escape-complete" : "ground-escape-aborted", BlockPos.containing(this.escapeTarget));
            this.escapeTarget = null;
            this.escapeWaypoints.clear();
            this.launchAnchor = reached ? null : position;
            baritone.getLookBehavior().clearTarget();
        } else {
            Rotation rotation = RotationUtils.calcRotationFromVec3d(Vec3.ZERO, direction, ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(rotation.withPitch(0), true);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private boolean launchSpaceClear(AABB box) {
        return ctx.world().noCollision(ctx.player(), box) && hazardFree(box);
    }

    private boolean hazardFree(AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (!ctx.world().isLoaded(pos) || ctx.world().getFluidState(pos).is(FluidTags.LAVA)
                    || ctx.world().getBlockState(pos).is(BlockTags.FIRE)) {
                return false;
            }
        }
        return true;
    }

    private PathingCommand tickLaunch() {
        baritone.getInputOverrideHandler().clearAllKeys();
        int tick = ++this.launchTicks;
        if (tick > 24 || (tick > 6 && ctx.player().onGround()) || ctx.player().isInLava()) {
            this.launchAim = null;
            this.launchAnchor = ctx.player().position();
            this.nextLaunchAttempt = ctx.player().tickCount + 40;
            baritone.getLookBehavior().clearTarget();
            traceFlight("launch-aborted", currentDestination());
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        baritone.getLookBehavior().updateTarget(this.launchAim, true);
        if (ctx.player().onGround() && tick <= 6) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        } else if (!ctx.player().onGround() && this.glideRequestTick == 0
                && ctx.player().getDeltaMovement().y < 0 && ctx.player().tryToStartFallFlying()) {
            ctx.player().connection.send(new ServerboundPlayerCommandPacket(ctx.player(),
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            this.glideRequestTick = tick;
            traceFlight("launch-glide-request", currentDestination());
        } else if (!ctx.player().onGround() && ctx.player().isFallFlying()) {
            Rotation actualAim = baritone.getLookBehavior().getAimProcessor().peekRotation(this.launchAim);
            if (ElytraLaunchPlanner.canIgnite(ctx.player(), this.launchAim, actualAim)) {
                if (this.behavior.useLaunchFirework(actualAim)) {
                    this.launchAim = null;
                    this.launchIgnitionRejected = false;
                    this.launchAnchor = null;
                    this.hasFlown = true;
                    this.state = State.FLYING;
                }
            } else if (!this.launchIgnitionRejected) {
                this.launchIgnitionRejected = true;
                traceFlight("launch-ignition-recheck-blocked", currentDestination());
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private void startPathAfterLaunch() {
        if (this.launchNeedsPath && (!this.behavior.pathManager.getPath().isEmpty()
                || this.behavior.pathManager.retryDestination())) {
            this.launchNeedsPath = false;
        }
    }

    public boolean launchIgnitionBlocked() {
        return this.launchIgnitionRejected;
    }

    static boolean canFinishFlight(double remainingDistance, boolean safetyLanding, boolean landRequested, boolean landingApproach) {
        return remainingDistance <= 48 || safetyLanding || landRequested || landingApproach;
    }

    private boolean shouldLandForSafety() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() < Baritone.settings().elytraMinimumDurability.value) {
            // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we can reasonably assume that the elytra will soon be broken without replacement
            return true;
        }

        return availableRockets() <= Baritone.settings().elytraMinFireworksBeforeLanding.value;
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getState() == EventState.PRE) {
            onLostControl();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IBaritone baritone) {
            super(baritone, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = 8;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private static boolean isInBounds(BlockPos pos) {
        return pos.getY() >= 0 && pos.getY() < 128;
    }

    private boolean isSafeBlock(Block block) {
        return block == Blocks.NETHERRACK || block == Blocks.GRAVEL || (block == Blocks.NETHER_BRICKS && Baritone.settings().elytraAllowLandOnNetherFortress.value);
    }

    private boolean isSafeBlock(BlockPos pos) {
        return isSafeBlock(ctx.world().getBlockState(pos).getBlock());
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
                || !isSafeBlock(pos.south())
                || !isSafeBlock(pos.east())
                || !isSafeBlock(pos.west())
                // corners
                || !isSafeBlock(pos.north().west())
                || !isSafeBlock(pos.north().east())
                || !isSafeBlock(pos.south().west())
                || !isSafeBlock(pos.south().east());
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= 0) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeBlock(block)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (block != Blocks.AIR) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private static final int LANDING_COLUMN_HEIGHT = 15;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        Queue<BetterBlockPos> queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - start.x) * (pos.x - start.x) + (pos.z - start.z) * (pos.z - start.z)).thenComparingInt(pos -> -pos.y));
        Set<BetterBlockPos> visited = new HashSet<>();
        LongOpenHashSet checkedPositions = new LongOpenHashSet();
        queue.add(start);

        while (!queue.isEmpty()) {
            BetterBlockPos pos = queue.poll();
            if (ctx.world().isLoaded(pos) && isInBounds(pos) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, checkedPositions);
                if (actualLandingSpot != null && isColumnAir(actualLandingSpot, LANDING_COLUMN_HEIGHT) && hasAirBubble(actualLandingSpot.above(LANDING_COLUMN_HEIGHT)) && !badLandingSpots.contains(actualLandingSpot.above(LANDING_COLUMN_HEIGHT))) {
                    return actualLandingSpot.above(LANDING_COLUMN_HEIGHT);
                }
                if (visited.add(pos.north())) queue.add(pos.north());
                if (visited.add(pos.east())) queue.add(pos.east());
                if (visited.add(pos.south())) queue.add(pos.south());
                if (visited.add(pos.west())) queue.add(pos.west());
                if (visited.add(pos.above())) queue.add(pos.above());
                if (visited.add(pos.below())) queue.add(pos.below());
            }
        }
        return null;
    }
}
