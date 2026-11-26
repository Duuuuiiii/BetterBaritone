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

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.process.ElytraProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import baritone.utils.PathRenderer;
import baritone.utils.accessor.IFireworkRocketEntity;
import com.mojang.blaze3d.vertex.BufferBuilder;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;

import static baritone.utils.BaritoneMath.fastCeil;
import static baritone.utils.BaritoneMath.fastFloor;

public final class ElytraBehavior implements Helper {
    private static final double LOW_SPEED_SQUARED = 0.9 * 0.9;
    private final Baritone baritone;
    private final IPlayerContext ctx;

    // Render stuff
    private final List<Pair<Vec3, Vec3>> clearLines;
    private final List<Pair<Vec3, Vec3>> blockedLines;
    private List<Vec3> simulationLine;
    private BlockPos aimPos;
    private List<BetterBlockPos> visiblePath;

    // :sunglasses:
    public final NetherPathfinderContext context;
    public final PathManager pathManager;
    private final ElytraProcess process;

    /**
     * Remaining cool-down ticks between firework usage
     */
    private int remainingFireworkTicks;

    /**
     * Remaining cool-down ticks after the player's position and rotation are reset by the server
     */
    private int remainingSetBackTicks;
    private int nextHazardReplan;
    private boolean destroyed;
    private int nextWallDiagnostic;
    private int nextRocketAttempt;
    private int nextRocketDiagnostic;
    private Vec3 lastWallContact;
    private int lastWallContactTick;

    public boolean landingMode;

    /**
     * The most recent minimum number of firework boost ticks, equivalent to {@code 10 * (1 + Flight)}
     * <p>
     * Updated every time a firework is automatically used
     */
    private int minimumBoostTicks;

    private boolean deployedFireworkLastTick;
    private int refillCooldown;
    private int rocketAckTicks;
    private int rocketsBeforeUse;
    private final int[] nextTickBoostCounter;

    private BlockStateInterface bsi;
    private final BlockStateOctreeInterface boi;
    public final BetterBlockPos destination;
    private final boolean appendDestination;

    private final ExecutorService solverExecutor;
    private Future<Solution> solver;
    private Solution pendingSolution;
    private boolean solveNextTick;

    private long timeLastCacheCull = 0L;

    // auto swap
    private int invTickCountdown = 0;
    private final Queue<Runnable> invTransactionQueue = new LinkedList<>();

    public ElytraBehavior(Baritone baritone, ElytraProcess process, BlockPos destination, boolean appendDestination) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.clearLines = new CopyOnWriteArrayList<>();
        this.blockedLines = new CopyOnWriteArrayList<>();
        this.pathManager = this.new PathManager();
        this.process = process;
        this.destination = new BetterBlockPos(destination);
        this.appendDestination = appendDestination;
        this.solverExecutor = Executors.newSingleThreadExecutor();
        this.nextTickBoostCounter = new int[2];

        this.context = new NetherPathfinderContext(Baritone.settings().elytraNetherSeed.value);
        this.boi = new BlockStateOctreeInterface(context);
    }

    public final class PathManager {

        public NetherPath path;
        private boolean completePath;
        private boolean recalculating;

        private int maxPlayerNear;
        private int ticksNearUnchanged;
        private int playerNear;
        private int nextExtensionAttempt;
        private int nextObstacleReplan;
        private Vec3 lastCollisionPosition;
        private int lastCollisionTick;
        private boolean touchingBlock;

        public PathManager() {
            // lol imagine initializing fields normally
            this.clear();
        }

        public void tick() {
            // Recalculate closest path node
            this.updatePlayerNear();
            final int prevMaxNear = this.maxPlayerNear;
            this.maxPlayerNear = Math.max(this.maxPlayerNear, this.playerNear);

            if (this.maxPlayerNear == prevMaxNear && ctx.player().isFallFlying()) {
                this.ticksNearUnchanged++;
            } else {
                this.ticksNearUnchanged = 0;
            }

            if (replanAfterRepeatedContact()) {
                return;
            }
            // Obstacles are more important than an incomplete path, handle those first.
            this.pathfindAroundObstacles();
            this.attemptNextSegment();
        }

        private boolean replanAfterRepeatedContact() {
            var player = ctx.player();
            boolean contact = player.isFallFlying() && !player.onGround()
                    && (player.horizontalCollision || player.verticalCollision);
            boolean started = contact && !this.touchingBlock;
            this.touchingBlock = contact;
            if (!started || landingMode || appendDestination) {
                return false;
            }

            int tick = player.tickCount;
            Vec3 position = player.position();
            boolean repeated = this.lastCollisionPosition != null
                    && tick - this.lastCollisionTick <= 80
                    && position.distanceToSqr(this.lastCollisionPosition) <= 12 * 12;
            this.lastCollisionPosition = position;
            this.lastCollisionTick = tick;
            if (!repeated || tick < this.nextObstacleReplan || !retryDestination()) {
                return false;
            }
            this.nextObstacleReplan = tick + 60;
            process.traceFlight("wall-contact-reroute", destination);
            return true;
        }

        public CompletableFuture<Void> pathToDestination() {
            return this.pathToDestination(ctx.playerFeet());
        }

        public boolean isRecalculating() {
            return this.recalculating;
        }

        public boolean retryDestination() {
            if (this.recalculating) {
                return false;
            }
            this.pathToDestination();
            return true;
        }

        public CompletableFuture<Void> pathToDestination(final BlockPos from) {
            this.recalculating = true;
            final long start = System.nanoTime();
            return this.path0(from, ElytraBehavior.this.destination, UnaryOperator.identity())
                    .thenRun(() -> {
                        if (this.path.isEmpty()) {
                            return;
                        }
                        final double distance = this.path.get(0).distanceTo(this.path.get(this.path.size() - 1));
                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute path to destination");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public CompletableFuture<Void> pathRecalcSegment(final OptionalInt upToIncl) {
            if (this.recalculating) {
                throw new IllegalStateException("already recalculating");
            }

            this.recalculating = true;
            final List<BetterBlockPos> after = upToIncl.isPresent() ? this.path.subList(upToIncl.getAsInt() + 1, this.path.size()) : Collections.emptyList();
            final boolean complete = this.completePath;
            final BlockPos rejoin = upToIncl.isPresent() ? this.path.get(upToIncl.getAsInt()) : null;
            this.nextObstacleReplan = ctx.player().tickCount + 20;

            return this.path0(ctx.playerFeet(), rejoin == null ? destination : rejoin,
                    segment -> rejoin == null ? segment : segment.spliceAt(rejoin, after, complete))
                    .exceptionallyCompose(error -> {
                        var player = ctx.player();
                        if (rejoin == null || !(error.getCause() instanceof PathCalculationException)
                                || player == null || context.isClosed()) {
                            return CompletableFuture.failedFuture(error);
                        }
                        // Keeping the old route is useful while searching, but retrying its blocked
                        // rejoin point would keep pulling the player back to the same corridor.
                        this.nextObstacleReplan = player.tickCount + 60;
                        process.traceFlight("route-rejoin-failed", rejoin);
                        process.traceFlight("route-replan-from-player", destination);
                        return this.path0(ctx.playerFeet(), destination, UnaryOperator.identity());
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logVerbose("Could not find a replacement route");
                                process.traceFlight("route-replan-failed", destination);
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void pathNextSegment(final int afterIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            final int retainedFrom = Math.max(0, Math.min(afterIncl, this.playerNear - 64));
            final List<BetterBlockPos> before = this.path.subList(retainedFrom, afterIncl + 1);
            final long start = System.nanoTime();
            final BetterBlockPos pathStart = this.path.get(afterIncl);

            this.nextExtensionAttempt = ctx.player().tickCount + 20;
            this.path0(pathStart, ElytraBehavior.this.destination, segment -> segment.extendFrom(pathStart, before))
                    .exceptionallyCompose(error -> {
                        var player = ctx.player();
                        if (!(error.getCause() instanceof PathCalculationException) || player == null
                                || context.isClosed() || landingMode || appendDestination
                                || player.distanceToSqr(pathStart.getCenter()) > 32 * 32) {
                            return CompletableFuture.failedFuture(error);
                        }
                        this.nextExtensionAttempt = player.tickCount + 60;
                        this.nextObstacleReplan = this.nextExtensionAttempt;
                        process.traceFlight("route-extension-replan-from-player", destination);
                        return this.path0(ctx.playerFeet(), destination, UnaryOperator.identity());
                    })
                    .thenRun(() -> {
                        final BetterBlockPos last = this.path.getLast();
                        if (last == null) {
                            return;
                        }
                        final double distance = pathStart.distanceTo(last);

                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logVerbose("No extension found for the current route");
                                process.traceFlight("route-extension-failed", pathStart);
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void clear() {
            this.path = NetherPath.emptyPath();
            this.completePath = true;
            this.recalculating = false;
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
            this.nextExtensionAttempt = 0;
            this.nextObstacleReplan = 0;
            this.lastCollisionPosition = null;
            this.touchingBlock = false;
        }

        private void setPath(final UnpackedSegment segment) {
            List<BetterBlockPos> path = segment.collect();
            if (ElytraBehavior.this.appendDestination) {
                BlockPos dest = ElytraBehavior.this.destination;
                BlockPos last = !path.isEmpty() ? path.get(path.size() - 1) : null;
                if (last != null && ElytraBehavior.this.clearView(Vec3.atLowerCornerOf(dest), Vec3.atLowerCornerOf(last), false)) {
                    path.add(new BetterBlockPos(dest));
                } else {
                    logDirect("unable to land at " + ElytraBehavior.this.destination);
                    process.landingSpotIsBad(ElytraBehavior.this, new BetterBlockPos(ElytraBehavior.this.destination));
                }
            }
            if (path.equals(this.path) && this.completePath == segment.isFinished()) {
                return;
            }
            this.path = new NetherPath(path);
            this.completePath = segment.isFinished();
            this.processTraceSegmentEnd();
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
        }

        private void processTraceSegmentEnd() {
            BetterBlockPos end = this.path.getLast();
            if (end != null) {
                process.traceFlight("route-segment-selected", end);
            }
        }

        public NetherPath getPath() {
            return this.path;
        }

        public int getNear() {
            return this.playerNear;
        }

        // mickey resigned
        private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            return context.pathFindAsync(src, dst)
                    .thenApply(UnpackedSegment::from)
                    .thenApply(operator)
                    .whenCompleteAsync((segment, error) -> {
                        if (error == null && !context.isClosed()) {
                            this.setPath(segment);
                        }
                    }, ctx.minecraft()::execute)
                    .thenApply(segment -> null);
        }

        private void pathfindAroundObstacles() {
            if (this.recalculating || ctx.player().tickCount < this.nextObstacleReplan) {
                return;
            }

            int rangeStartIncl = playerNear;
            int rangeEndExcl = playerNear;
            while (rangeEndExcl < path.size() && context.hasChunk(new ChunkPos(path.get(rangeEndExcl)))) {
                rangeEndExcl++;
            }
            // rangeEndExcl now represents an index either not in the path, or just outside render distance
            if (rangeStartIncl >= rangeEndExcl) {
                // not loaded yet?
                return;
            }
            final BetterBlockPos rangeStart = path.get(rangeStartIncl);
            if (!ElytraBehavior.this.passable(rangeStart.x, rangeStart.y, rangeStart.z, false)) {
                // we're in a wall
                return; // previous iterations of this function SHOULD have fixed this by now :rage_cat:
            }

            if (ElytraBehavior.this.process.state != ElytraProcess.State.LANDING && this.ticksNearUnchanged > 100) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1))
                        .thenRun(() -> {
                            logVerbose("Recalculating segment, no progress in last 100 ticks");
                        });
                this.ticksNearUnchanged = 0;
                return;
            }

            boolean canSeeAny = false;
            for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {
                if (ElytraBehavior.this.clearView(ctx.playerFeetAsVec(), this.path.getVec(i), false) || ElytraBehavior.this.clearView(ctx.playerHead(), this.path.getVec(i), false)) {
                    canSeeAny = true;
                }
                if (!ElytraBehavior.this.clearView(this.path.getVec(i), this.path.getVec(i + 1), false)) {
                    // obstacle. where do we return to pathing?
                    // if the end of render distance is closer to goal, then that's fine, otherwise we'd be "digging our hole deeper" and making an already bad backtrack worse
                    OptionalInt rejoinMainPathAt;
                    if (this.path.get(rangeEndExcl - 1).distanceSq(ElytraBehavior.this.destination) < ctx.playerFeet().distanceSq(ElytraBehavior.this.destination)) {
                        rejoinMainPathAt = OptionalInt.of(rangeEndExcl - 1); // rejoin after current render distance
                    } else {
                        rejoinMainPathAt = OptionalInt.empty(); // large backtrack detected. ignore render distance, rejoin later on
                    }

                    final BetterBlockPos blockage = this.path.get(i);
                    final double distance = ctx.playerFeet().distanceTo(this.path.get(rejoinMainPathAt.orElse(path.size() - 1)));

                    final long start = System.nanoTime();
                    this.pathRecalcSegment(rejoinMainPathAt)
                            .thenRun(() -> {
                                logVerbose(String.format("Recalculated segment around path blockage near %s %s %s (next %.1f blocks in %.4f seconds)",
                                        SettingsUtil.maybeCensor(blockage.x),
                                        SettingsUtil.maybeCensor(blockage.y),
                                        SettingsUtil.maybeCensor(blockage.z),
                                        distance,
                                        (System.nanoTime() - start) / 1e9d
                                ));
                            });
                    return;
                }
            }
            if (!canSeeAny && rangeStartIncl < rangeEndExcl - 2 && process.state != ElytraProcess.State.GET_TO_JUMP) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1)).thenRun(() -> logVerbose("Recalculated segment since no path points were visible"));
            }
        }

        private void attemptNextSegment() {
            if (this.recalculating || ctx.player().tickCount < this.nextExtensionAttempt) {
                return;
            }

            final int last = this.path.size() - 1;
            if (!this.completePath && last >= 0 && ctx.world().isLoaded(this.path.get(last))) {
                this.pathNextSegment(last);
            }
        }

        public void updatePlayerNear() {
            if (this.path.isEmpty()) {
                return;
            }

            int index = this.playerNear;
            final BetterBlockPos pos = ctx.playerFeet();
            for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i >= Math.max(index - 50, 0); i--) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 50, path.size()); i++) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            this.playerNear = index;
        }

        public boolean isComplete() {
            return this.completePath;
        }
    }

    public void onRenderPass(RenderEvent event) {

        final Settings settings = Baritone.settings();
        if (this.visiblePath != null && settings.elytraRenderRoute.value) {
            PathRenderer.drawPath(event.getModelViewStack(), this.visiblePath, 0, settings.elytraRouteColor.value, settings.elytraRenderThroughWalls.value, 0, 0, 0.0D);
        }
        if (this.aimPos != null && settings.elytraRenderAim.value) {
            PathRenderer.drawGoal(event.getModelViewStack(), ctx, new GoalBlock(this.aimPos), event.getPartialTicks(), Color.GREEN);
        }
        if (!this.clearLines.isEmpty() && settings.elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.GREEN);
            for (Pair<Vec3, Vec3> line : this.clearLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (!this.blockedLines.isEmpty() && Baritone.settings().elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE);
            for (Pair<Vec3, Vec3> line : this.blockedLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (this.simulationLine != null && Baritone.settings().elytraRenderSimulation.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(settings.elytraSimulationColor.value);
            final Vec3 offset = ctx.player().getPosition(event.getPartialTicks());
            for (int i = 0; i < this.simulationLine.size() - 1; i++) {
                final Vec3 src = this.simulationLine.get(i).add(offset);
                final Vec3 dst = this.simulationLine.get(i + 1).add(offset);
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), src, dst, settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
    }

    public void onChunkEvent(ChunkEvent event) {
        if (event.isPostPopulate() && this.context != null) {
            final LevelChunk chunk = ctx.world().getChunk(event.getX(), event.getZ());
            this.context.queueForPacking(chunk);
        }
    }

    public void onBlockChange(BlockChangeEvent event) {
        this.context.queueBlockUpdate(event);
    }

    public void onReceivePacket(PacketEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            ctx.minecraft().execute(() -> {
                this.remainingSetBackTicks = Baritone.settings().elytraFireworkSetbackUseDelay.value;
                this.process.traceFlight("server-position-correction", this.destination);
            });
        }
    }

    public void pathTo() {
        if (!Baritone.settings().elytraAutoJump.value || ctx.player().isFallFlying()) {
            this.pathManager.pathToDestination();
        }
    }

    public void destroy() {
        this.destroyed = true;
        if (this.solver != null) {
            this.solver.cancel(true);
        }
        this.solverExecutor.shutdown();
        try {
            while (!this.solverExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        this.context.destroy();
    }

    public void repackChunks() {
        ChunkSource chunkProvider = ctx.world().getChunkSource();

        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    this.context.queueForPacking(chunk);
                }
            }
        }
    }

    public void onTick(boolean updatePath) {
        synchronized (this.context.cullingLock) {
            this.onTick0(updatePath);
        }
        final long now = System.currentTimeMillis();
        if ((now - this.timeLastCacheCull) / 1000 > Baritone.settings().elytraTimeBetweenCacheCullSecs.value) {
            this.context.queueCacheCulling(ctx.player().chunkPosition().x, ctx.player().chunkPosition().z, Baritone.settings().elytraCacheCullDistance.value, this.boi);
            this.timeLastCacheCull = now;
        }
    }

    private void onTick0(boolean updatePath) {
        if (ctx.player().onGround() && !ctx.player().isFallFlying()) {
        }
        // Fetch the previous solution, regardless of if it's going to be used
        this.pendingSolution = null;
        if (this.solver != null) {
            try {
                this.pendingSolution = this.solver.get();
            } catch (Exception ignored) {
                // it doesn't matter if get() fails since the solution can just be recalculated synchronously
            } finally {
                this.solver = null;
            }
        }

        tickInventoryTransactions();
        refillHotbar();
        checkRocketUse();

        // Certified mojang employee incident
        if (this.remainingFireworkTicks > 0) {
            this.remainingFireworkTicks--;
        }
        if (this.remainingSetBackTicks > 0) {
            this.remainingSetBackTicks--;
        }
        if (this.remainingFireworkTicks == 0 && this.getAttachedFirework().isEmpty()) {
            this.minimumBoostTicks = 0;
        }

        // Reset rendered elements
        this.clearLines.clear();
        this.blockedLines.clear();
        this.simulationLine = null;
        this.aimPos = null;

        final List<BetterBlockPos> path = this.pathManager.getPath();
        this.visiblePath = null;
        if (path.isEmpty()) {
            return;
        } else if (this.destination == null) {
            this.pathManager.clear();
            return;
        }

        if (updatePath) {
            this.bsi = new BlockStateInterface(ctx);
            this.pathManager.tick();
        } else {
            this.pathManager.updatePlayerNear();
        }

        final int playerNear = this.pathManager.getNear();
        this.visiblePath = path.subList(
                Math.max(playerNear - 30, 0),
                Math.min(playerNear + 100, path.size())
        );
    }

    /**
     * Called by {@link baritone.process.ElytraProcess#onTick(boolean, boolean)} when the process is in control and the player is flying
     */
    public void tick() {
        var player = ctx.player();
        if (player.horizontalCollision) {
            recordWallContact();
        }
        Solution solution = player.isInLava() || this.bsi == null || this.pathManager.getPath().isEmpty()
                ? null : solveCruise();
        Rotation route = solution != null && solution.solvedPitch ? solution.rotation : null;
        FallingLava fallingLava = new FallingLava(ctx.world());
        Rotation heading = chooseHeading(route, fallingLava);
        if (route == null) {
            retryHazardRoute();
        }

        var look = baritone.getLookBehavior();
        if (heading == route) {
            this.aimPos = new BetterBlockPos(solution.goingTo.x, solution.goingTo.y, solution.goingTo.z);
        }
        look.updateTarget(boostIfSlow(heading, heading == route ? solution : null, fallingLava), false);
    }

    private Solution solveCruise() {
        trySwapElytra();
        SolverContext context = this.new SolverContext(false);
        this.solveNextTick = true;
        if (this.deployedFireworkLastTick) {
            this.nextTickBoostCounter[context.boost.isBoosted() ? 1 : 0]++;
            this.deployedFireworkLastTick = false;
        }
        return this.pendingSolution != null && this.pendingSolution.context.equals(context)
                ? this.pendingSolution : solveAngles(context);
    }

    private Rotation chooseHeading(Rotation route, FallingLava fallingLava) {
        var player = ctx.player();
        var aim = baritone.getLookBehavior().getAimProcessor();
        Rotation current = ctx.playerRotations();
        Rotation preferred = route == null ? current : route;
        Rotation actual = route == null ? current : aim.peekRotation(route);
        int boostTicks = attachedBoostTicks();
        boolean inLava = player.isInLava();
        boolean heat = inLava || ElytraLavaGuard.threatens(player, actual, boostTicks, fallingLava);
        if (!heat) {
            return preferred;
        }
        if (inLava) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }

        var escape = ElytraLavaGuard.glideAside(player, preferred, current, boostTicks, aim::peekRotation, fallingLava);
        if (!inLava && escape != null && escape.clearsHorizon()) {
            traceFlightDecision("escape-heading-selected");
            return escape.rotation();
        }
        if (heat && boostTicks == 0 && this.remainingSetBackTicks <= 0 && this.rocketAckTicks == 0) {
            Rotation boosted = tryLavaEscapeBoost(preferred, fallingLava);
            if (boosted != null) {
                return boosted;
            }
        }
        retryHazardRoute();
        traceFlightDecision(escape == null ? "escape-no-clear-heading" : "escape-partial-heading");
        return escape == null ? current : escape.rotation();
    }

    private Rotation tryLavaEscapeBoost(Rotation heading, FallingLava fallingLava) {
        var look = baritone.getLookBehavior();
        Rotation escape = ElytraLavaGuard.climb(ctx.player(), heading, 18,
                look.getAimProcessor()::peekRotation, fallingLava);
        if (escape == null || !useFirework(true, look.getAimProcessor().peekRotation(escape), ctx.player().isInLava())) {
            return null;
        }
        this.process.traceFlight("escape-rocket", this.destination);
        return escape;
    }

    private int attachedBoostTicks() {
        return getAttachedFirework().map(rocket -> Math.max(1, minimumBoostTicks - rocket.tickCount + 8)).orElse(remainingFireworkTicks > 0 ? minimumBoostTicks + 8 : 0);
    }

    private void retryHazardRoute() {
        int tick = ctx.player().tickCount;
        if (tick >= this.nextHazardReplan && this.pathManager.retryDestination()) {
            this.nextHazardReplan = tick + 100;
        }
    }

    private void traceFlightDecision(String event) {
        int tick = ctx.player().tickCount;
        if (tick >= this.nextWallDiagnostic) {
            this.nextWallDiagnostic = tick + 20;
            this.process.traceFlight(event, this.destination);
        }
    }

    public void onPostTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN && this.solveNextTick) {
            // We're at the end of the tick, the player's position likely updated and the closest path node could've
            // changed. Updating it now will avoid unnecessary recalculation on the main thread.
            this.pathManager.updatePlayerNear();

            final SolverContext context = this.new SolverContext(true);
            this.solver = this.solverExecutor.submit(() -> this.solveAngles(context));
            this.solveNextTick = false;
        }
    }

    private Solution solveAngles(final SolverContext context) {
        final NetherPath path = context.path;
        final int playerNear = landingMode ? path.size() - 1 : context.playerNear;
        final Vec3 start = context.start;
        Solution solution = null;

        for (int relaxation = 0; relaxation < 3; relaxation++) { // try for a strict solution first, then relax more and more (if we're in a corner or near some blocks, it will have to relax its constraints a bit)
            int[] heights = context.boost.isBoosted() ? new int[]{20, 10, 5, 0} : new int[]{0}; // attempt to gain height, if we can, so as not to waste the boost
            int lookahead = relaxation == 0 ? 2 : 3; // ideally this would be expressed as a distance in blocks, rather than a number of voxel steps
            //int minStep = Math.max(0, playerNear - relaxation);
            int minStep = playerNear;

            for (int i = Math.min(playerNear + 20, path.size() - 1); i >= minStep; i--) {
                final List<Pair<Vec3, Integer>> candidates = new ArrayList<>();
                for (int dy : heights) {
                    if (relaxation == 0 || i == minStep) {
                        // no interp
                        candidates.add(new Pair<>(path.getVec(i), dy));
                    } else if (relaxation == 1) {
                        final double[] interps = new double[]{1.0, 0.75, 0.5, 0.25};
                        for (double interp : interps) {
                            final Vec3 dest = interp == 1.0
                                    ? path.getVec(i)
                                    : path.getVec(i).scale(interp).add(path.getVec(i - 1).scale(1.0 - interp));
                            candidates.add(new Pair<>(dest, dy));
                        }
                    } else {
                        // Create a point along the segment every block
                        final Vec3 delta = path.getVec(i).subtract(path.getVec(i - 1));
                        final int steps = fastFloor(delta.length());
                        final Vec3 step = delta.normalize();
                        Vec3 stepped = path.getVec(i);
                        for (int interp = 0; interp < steps; interp++) {
                            candidates.add(new Pair<>(stepped, dy));
                            stepped = stepped.subtract(step);
                        }
                    }
                }

                for (final Pair<Vec3, Integer> candidate : candidates) {
                    final Integer augment = candidate.second();
                    Vec3 dest = candidate.first().add(0, augment, 0);
                    if (landingMode) {
                        dest = dest.add(0.5, 0.5, 0.5);
                    }

                    if (augment != 0) {
                        if (i + lookahead >= path.size()) {
                            continue;
                        }
                        if (start.distanceTo(dest) < 40) {
                            if (!this.clearView(dest, path.getVec(i + lookahead).add(0, augment, 0), false)
                                    || !this.clearView(dest, path.getVec(i + lookahead), false)) {
                                // aka: don't go upwards if doing so would prevent us from being able to see the next position **OR** the modified next position
                                continue;
                            }
                        } else {
                            // but if it's far away, allow gaining altitude if we could lose it again by the time we get there
                            if (!this.clearView(dest, path.getVec(i), false)) {
                                continue;
                            }
                        }
                    }

                    final double minAvoidance = Baritone.settings().elytraMinimumAvoidance.value;
                    final Double growth = relaxation == 2 ? null
                            : relaxation == 0 ? 2 * minAvoidance : minAvoidance;

                    if (this.isHitboxClear(context, dest, growth)) {
                        // Yaw is trivial, just calculate the rotation required to face the destination
                        final float yaw = RotationUtils.calcRotationFromVec3d(start, dest, ctx.playerRotations()).getYaw();

                        final Pair<Float, Boolean> pitch = this.solvePitch(context, dest, relaxation);
                        if (pitch == null) {
                            solution = new Solution(context, new Rotation(yaw, ctx.playerRotations().getPitch()), null, false, false);
                            continue;
                        }

                        // A solution was found with yaw AND pitch, so just immediately return it.
                        return new Solution(context, new Rotation(yaw, pitch.first()), dest, true, pitch.second());
                    }
                }
            }
        }
        return solution;
    }

    private Rotation boostIfSlow(Rotation heading, Solution route, FallingLava fallingLava) {
        var player = ctx.player();
        int tick = player.tickCount;
        if (this.landingMode || this.appendDestination || player.isInLava()
                || this.remainingSetBackTicks > 0 || this.rocketAckTicks > 0 || tick < this.nextRocketAttempt
                || attachedBoostTicks() > 0) {
            return heading;
        }

        double speed = Baritone.settings().elytraFireworkSpeed.value;
        Vec3 motion = player.getDeltaMovement();
        boolean forced = route != null && route.forceUseFirework;
        if (!forced && !belowBoostSpeed(motion, speed)) {
            return heading;
        }
        if (!forced && Baritone.settings().elytraConserveFireworks.value
                && motion.horizontalDistanceSqr() >= LOW_SPEED_SQUARED
                && (route == null || player.getY() > route.goingTo.y + 5)) {
            return heading;
        }

        var aim = baritone.getLookBehavior().getAimProcessor();
        Rotation target = heading;
        Rotation actual = aim.peekRotation(target);
        if (!ElytraLaunchPlanner.canBoost(player, actual, fallingLava)) {
            target = heading.withPitch(0);
            actual = aim.peekRotation(target);
            if (heading.getPitch() == 0 || !ElytraLaunchPlanner.canBoost(player, actual, fallingLava)) {
                traceFlightDecision("rocket-held-unsafe-corridor");
                return heading;
            }
        }
        return useFirework(forced, actual) ? target : heading;
    }

    static boolean belowBoostSpeed(Vec3 motion, double threshold) {
        return motion.horizontalDistanceSqr() < threshold * threshold;
    }

    public boolean useLaunchFirework(Rotation aim) {
        if (this.remainingFireworkTicks > 0 || this.remainingSetBackTicks > 0
                || ctx.player().horizontalCollision || !useFirework(true, aim, true)) {
            return false;
        }
        this.lastWallContact = null;
        this.process.traceFlight("launch-rocket-request", this.destination);
        return true;
    }

    private boolean useFirework(boolean forced, Rotation aim) {
        return useFirework(forced, aim, false);
    }

    private boolean useFirework(boolean forced, Rotation aim, boolean escapeOrValidatedLaunch) {
        var player = ctx.player();
        if (player.tickCount < this.nextRocketAttempt) {
            return false;
        }
        if (!escapeOrValidatedLaunch && boostBlockedByWall()) {
            return waitForRocket("rocket-held-recent-wall-contact");
        }
        var inventory = player.getInventory();
        int rocketSlot = hotbarIndex(Baritone.settings().elytraRocketSlot.value);
        if (isFireworks(inventory.getItem(rocketSlot))) {
            inventory.setSelectedSlot(rocketSlot);
        } else {
            baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isFireworks);
        }
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (!isFireworks(player.getMainHandItem())) {
            if (!isFireworks(player.getOffhandItem())) {
                return waitForRocket(this.process.availableRockets() > 0 ? "rocket-slot-unavailable" : "rocket-inventory-empty");
            }
            hand = InteractionHand.OFF_HAND;
        }
        logVerbose("attempting to use firework" + (forced ? " (forced)" : ""));
        int before = this.process.availableRockets();
        int duration = getFireworkBoost(ctx.player().getItemInHand(hand)).orElse(0);
        InteractionHand chosenHand = hand;
        var result = ElytraLaunchPlanner.useRocketWithAim(player, aim,
                () -> ctx.playerController().processRightClick(ctx.player(), ctx.world(), chosenHand));
        if (!result.consumesAction()) {
            return waitForRocket("rocket-use-rejected");
        }
        this.rocketsBeforeUse = before;
        this.rocketAckTicks = 10;
        this.minimumBoostTicks = 10 * (1 + duration);
        this.remainingFireworkTicks = 10;
        this.deployedFireworkLastTick = true;
        this.process.traceFlight("rocket-use-sent", this.destination);
        return true;
    }

    private boolean waitForRocket(String event) {
        int tick = ctx.player().tickCount;
        this.nextRocketAttempt = tick + 3;
        if (tick >= this.nextRocketDiagnostic) {
            this.nextRocketDiagnostic = tick + 20;
            this.process.traceFlight(event, this.destination);
        }
        return false;
    }

    public void recordWallContact() {
        this.lastWallContact = ctx.player().position();
        this.lastWallContactTick = ctx.player().tickCount;
    }

    private boolean boostBlockedByWall() {
        var player = ctx.player();
        return player.horizontalCollision || this.lastWallContact != null
                && !canBoostAfterWall(player.tickCount - this.lastWallContactTick,
                player.position().subtract(this.lastWallContact).horizontalDistanceSqr());
    }

    static boolean canBoostAfterWall(int ticksSinceContact, double distanceSquared) {
        return ticksSinceContact >= 12 && (distanceSquared >= 16 || ticksSinceContact >= 40);
    }

    private void checkRocketUse() {
        if (this.rocketAckTicks <= 0) {
            return;
        }
        if (this.process.availableRockets() < this.rocketsBeforeUse) {
            this.rocketAckTicks = 0;
            this.process.onRocketUsed();
        } else if (--this.rocketAckTicks == 0) {
            this.process.traceFlight("rocket-unconfirmed", this.destination);
        }
    }

    private final class SolverContext {

        public final NetherPath path;
        public final int playerNear;
        public final Vec3 start;
        public final Vec3 motion;
        public final AABB boundingBox;
        public final boolean ignoreLava;
        public final FireworkBoost boost;
        public final IAimProcessor aimProcessor;

        /**
         * Creates a new SolverContext using the current state of the path, player, and firework boost at the time of
         * construction.
         *
         * @param async Whether the computation is being done asynchronously at the end of a game tick.
         */
        public SolverContext(boolean async) {
            this.path = ElytraBehavior.this.pathManager.getPath();
            this.playerNear = ElytraBehavior.this.pathManager.getNear();

            this.start = ctx.playerFeetAsVec();
            this.motion = ctx.playerMotion();
            this.boundingBox = ctx.player().getBoundingBox();
            this.ignoreLava = ctx.player().isInLava();

            final Integer fireworkTicksExisted;
            if (async && ElytraBehavior.this.deployedFireworkLastTick) {
                final int[] counter = ElytraBehavior.this.nextTickBoostCounter;
                fireworkTicksExisted = counter[1] > counter[0] ? 0 : null;
            } else {
                fireworkTicksExisted = ElytraBehavior.this.getAttachedFirework().map(e -> e.tickCount).orElse(null);
            }
            this.boost = new FireworkBoost(fireworkTicksExisted, ElytraBehavior.this.minimumBoostTicks);

            ITickableAimProcessor aim = ElytraBehavior.this.baritone.getLookBehavior().getAimProcessor().fork();
            if (async) {
                // async computation is done at the end of a tick, advance by 1 to prepare for the next tick
                aim.advance(1);
            }
            this.aimProcessor = aim;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != SolverContext.class) {
                return false;
            }

            SolverContext other = (SolverContext) o;
            return this.path == other.path  // Contents aren't modified, just compare by reference
                    && this.playerNear == other.playerNear
                    && Objects.equals(this.start, other.start)
                    && Objects.equals(this.motion, other.motion)
                    && Objects.equals(this.boundingBox, other.boundingBox)
                    && this.ignoreLava == other.ignoreLava
                    && Objects.equals(this.boost, other.boost);
        }
    }

    private static final class FireworkBoost {

        private final Integer fireworkTicksExisted;
        private final int minimumBoostTicks;
        private final int maximumBoostTicks;

        /**
         * @param fireworkTicksExisted The ticksExisted of the attached firework entity, or {@code null} if no entity.
         * @param minimumBoostTicks    The minimum number of boost ticks that the attached firework entity, if any, will
         *                             provide.
         */
        public FireworkBoost(final Integer fireworkTicksExisted, final int minimumBoostTicks) {
            this.fireworkTicksExisted = fireworkTicksExisted;

            // this.lifetime = 10 * i + this.rand.nextInt(6) + this.rand.nextInt(7);
            this.minimumBoostTicks = minimumBoostTicks;
            this.maximumBoostTicks = minimumBoostTicks + 11;
        }

        public boolean isBoosted() {
            return this.fireworkTicksExisted != null;
        }

        /**
         * @return The guaranteed number of remaining ticks with boost
         */
        public int getGuaranteedBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.minimumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        /**
         * @return The maximum number of remaining ticks with boost
         */
        public int getMaximumBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.maximumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != FireworkBoost.class) {
                return false;
            }

            FireworkBoost other = (FireworkBoost) o;
            if (!this.isBoosted() && !other.isBoosted()) {
                return true;
            }

            return Objects.equals(this.fireworkTicksExisted, other.fireworkTicksExisted)
                    && this.minimumBoostTicks == other.minimumBoostTicks
                    && this.maximumBoostTicks == other.maximumBoostTicks;
        }
    }

    private static final class PitchResult {

        public final float pitch;
        public final double dot;
        public final List<Vec3> steps;

        public PitchResult(float pitch, double dot, List<Vec3> steps) {
            this.pitch = pitch;
            this.dot = dot;
            this.steps = steps;
        }
    }

    private static final class Solution {

        public final SolverContext context;
        public final Rotation rotation;
        public final Vec3 goingTo;
        public final boolean solvedPitch;
        public final boolean forceUseFirework;

        public Solution(SolverContext context, Rotation rotation, Vec3 goingTo, boolean solvedPitch, boolean forceUseFirework) {
            this.context = context;
            this.rotation = rotation;
            this.goingTo = goingTo;
            this.solvedPitch = solvedPitch;
            this.forceUseFirework = forceUseFirework;
        }
    }

    public static boolean isFireworks(final ItemStack itemStack) {
        if (itemStack.getItem() != Items.FIREWORK_ROCKET) {
            return false;
        }
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        return fw != null && fw.explosions().isEmpty();
    }

    private static OptionalInt getFireworkBoost(final ItemStack itemStack) {
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        if (fw != null && fw.explosions().isEmpty()) {
            return OptionalInt.of(fw.flightDuration());
        }
        return OptionalInt.empty();
    }

    private Optional<FireworkRocketEntity> getAttachedFirework() {
        return ctx.entitiesStream()
                .filter(x -> x instanceof FireworkRocketEntity)
                .filter(x -> Objects.equals(((IFireworkRocketEntity) x).getBoostedEntity(), ctx.player()))
                .map(x -> (FireworkRocketEntity) x)
                .findFirst();
    }

    private boolean isHitboxClear(final SolverContext context, final Vec3 dest, final Double growAmount) {
        return this.isHitboxClear(context.start, dest, context.boundingBox, growAmount, context.ignoreLava);
    }

    private boolean isHitboxClear(Vec3 start, Vec3 dest, AABB bounds, Double growAmount, boolean ignoreLava) {
        if (!this.clearView(start, dest, ignoreLava)) {
            return false;
        }
        if (growAmount == null) {
            return true;
        }

        final AABB bb = bounds.inflate(growAmount);

        final double ox = dest.x - start.x;
        final double oy = dest.y - start.y;
        final double oz = dest.z - start.z;

        final double[] src = new double[]{
                bb.minX, bb.minY, bb.minZ,
                bb.minX, bb.minY, bb.maxZ,
                bb.minX, bb.maxY, bb.minZ,
                bb.minX, bb.maxY, bb.maxZ,
                bb.maxX, bb.minY, bb.minZ,
                bb.maxX, bb.minY, bb.maxZ,
                bb.maxX, bb.maxY, bb.minZ,
                bb.maxX, bb.maxY, bb.maxZ,
        };
        final double[] dst = new double[]{
                bb.minX + ox, bb.minY + oy, bb.minZ + oz,
                bb.minX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.maxZ + oz,
        };

        // Use non-batching method without early failure
        if (Baritone.settings().elytraRenderHitboxRaytraces.value) {
            boolean clear = true;
            for (int i = 0; i < 8; i++) {
                final Vec3 s = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
                final Vec3 d = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
                // Don't forward ignoreLava since the batch call doesn't care about it
                if (!this.clearView(s, d, false)) {
                    clear = false;
                }
            }
            return clear;
        }

        return this.context.raytrace(8, src, dst, NetherPathfinderContext.Visibility.ALL);
    }

    public boolean clearView(Vec3 start, Vec3 dest, boolean ignoreLava) {
        final boolean clear;
        if (!ignoreLava) {
            // if start == dest then the cpp raytracer dies
            clear = start.equals(dest) || this.context.raytrace(start, dest);
        } else {
            clear = ctx.world().clip(new ClipContext(start, dest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS;
        }

        if (Baritone.settings().elytraRenderRaytraces.value) {
            (clear ? this.clearLines : this.blockedLines).add(new Pair<>(start, dest));
        }
        return clear;
    }

    private static FloatArrayList pitchesToSolveFor(final float goodPitch, final boolean desperate) {
        final float minPitch = desperate ? -90 : Math.max(goodPitch - Baritone.settings().elytraPitchRange.value, -89);
        final float maxPitch = desperate ? 90 : Math.min(goodPitch + Baritone.settings().elytraPitchRange.value, 89);

        final FloatArrayList pitchValues = new FloatArrayList(fastCeil(maxPitch - minPitch) + 1);
        for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
            pitchValues.add(pitch);
        }
        for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
            pitchValues.add(pitch);
        }

        return pitchValues;
    }

    @FunctionalInterface
    private interface IntTriFunction<T> {
        T apply(int first, int second, int third);
    }

    private static final class IntTriple {
        public final int first;
        public final int second;
        public final int third;

        public IntTriple(int first, int second, int third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    private Pair<Float, Boolean> solvePitch(final SolverContext context, final Vec3 goal, final int relaxation) {
        final boolean desperate = relaxation == 2;
        final float goodPitch = RotationUtils.calcRotationFromVec3d(context.start, goal, ctx.playerRotations()).getPitch();
        final FloatArrayList pitches = pitchesToSolveFor(goodPitch, desperate);

        final IntTriFunction<PitchResult> solve = (ticks, ticksBoosted, ticksBoostDelay) ->
                this.solvePitch(context, goal, relaxation, pitches.iterator(), ticks, ticksBoosted, ticksBoostDelay);

        final List<IntTriple> tests = new ArrayList<>();

        if (context.boost.isBoosted()) {
            final int guaranteed = context.boost.getGuaranteedBoostTicks();
            if (guaranteed == 0) {
                // uncertain when boost will run out
                final int lookahead = Math.max(4, 10 - context.boost.getMaximumBoostTicks());
                tests.add(new IntTriple(lookahead, 1, 0));
            } else if (guaranteed <= 5) {
                // boost will run out within 5 ticks
                tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
            } else {
                // there's plenty of guaranteed boost
                tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
            }
        }

        // Standard test, assume (not) boosted for entire duration
        final int ticks = desperate ? 3 : context.boost.isBoosted() ? Math.max(5, context.boost.getGuaranteedBoostTicks()) : Baritone.settings().elytraSimulationTicks.value;
        tests.add(new IntTriple(ticks, context.boost.isBoosted() ? ticks : 0, 0));

        final Optional<PitchResult> result = tests.stream()
                .map(i -> solve.apply(i.first, i.second, i.third))
                .filter(Objects::nonNull)
                .findFirst();
        if (result.isPresent()) {
            return new Pair<>(result.get().pitch, false);
        }

        // If we used a firework would we be able to get out of the current situation??? perhaps
        if (desperate) {
            final List<IntTriple> testsBoost = new ArrayList<>();
            testsBoost.add(new IntTriple(ticks, 10, 3));
            testsBoost.add(new IntTriple(ticks, 10, 2));
            testsBoost.add(new IntTriple(ticks, 10, 1));

            final Optional<PitchResult> resultBoost = testsBoost.stream()
                    .map(i -> solve.apply(i.first, i.second, i.third))
                    .filter(Objects::nonNull)
                    .findFirst();
            if (resultBoost.isPresent()) {
                return new Pair<>(resultBoost.get().pitch, true);
            }
        }

        return null;
    }

    private PitchResult solvePitch(final SolverContext context, final Vec3 goal, final int relaxation,
                                   final FloatIterator pitches, final int ticks, final int ticksBoosted,
                                   final int ticksBoostDelay) {
        // we are at a certain velocity, but we have a target velocity
        // what pitch would get us closest to our target velocity?
        // yaw is easy so we only care about pitch

        final Vec3 goalDelta = goal.subtract(context.start);
        final Vec3 goalDirection = goalDelta.normalize();

        final Deque<PitchResult> bestResults = new ArrayDeque<>();

        while (pitches.hasNext()) {
            final float pitch = pitches.nextFloat();
            final List<Vec3> displacement = this.simulate(
                    context,
                    goalDelta,
                    pitch,
                    ticks,
                    ticksBoosted,
                    ticksBoostDelay
            );
            if (displacement == null) {
                continue;
            }
            final Vec3 last = displacement.get(displacement.size() - 1);
            double goodness = goalDirection.dot(last.normalize());
            if (landingMode) {
                goodness = -goalDelta.subtract(last).length();
            }
            final PitchResult bestSoFar = bestResults.peek();
            if (bestSoFar == null || goodness > bestSoFar.dot) {
                bestResults.push(new PitchResult(pitch, goodness, displacement));
            }
        }

        outer:
        for (final PitchResult result : bestResults) {
            if (relaxation < 2) {
                // Ensure that the goal is visible along the entire simulated path
                // Reverse order iteration since the last position is most likely to fail
                for (int i = result.steps.size() - 1; i >= 1; i--) {
                    if (!clearView(context.start.add(result.steps.get(i)), goal, context.ignoreLava)) {
                        continue outer;
                    }
                }
            } else {
                // Ensure that the goal is visible from the final position
                if (!clearView(context.start.add(result.steps.get(result.steps.size() - 1)), goal, context.ignoreLava)) {
                    continue;
                }
            }

            this.simulationLine = result.steps;
            return result;
        }
        return null;
    }

    private List<Vec3> simulate(final SolverContext context, final Vec3 goalDelta, final float pitch, final int ticks,
                                final int ticksBoosted, final int ticksBoostDelay) {
        final ITickableAimProcessor aimProcessor = context.aimProcessor.fork();
        Vec3 delta = goalDelta;
        Vec3 motion = context.motion;
        AABB hitbox = context.boundingBox;
        List<Vec3> displacement = new ArrayList<>(ticks + 1);
        displacement.add(Vec3.ZERO);
        int remainingTicksBoosted = ticksBoosted;

        for (int i = 0; i < ticks; i++) {
            if (delta.lengthSqr() < 1) {
                break;
            }
            final Rotation rotation = aimProcessor.nextRotation(
                    RotationUtils.calcRotationFromVec3d(Vec3.ZERO, delta, ctx.playerRotations()).withPitch(pitch)
            );
            final Vec3 lookDirection = RotationUtils.calcLookDirectionFromRotation(rotation);

            motion = ElytraLaunchPlanner.glide(motion, lookDirection, rotation.getPitch());
            delta = delta.subtract(motion);

            if (!ElytraLaunchPlanner.clearSweep(hitbox.inflate(0.01), motion,
                    box -> this.isPassableVolume(box, context.ignoreLava))) {
                return null;
            }

            hitbox = hitbox.move(motion);
            displacement.add(displacement.get(displacement.size() - 1).add(motion));

            if (i >= ticksBoostDelay && remainingTicksBoosted-- > 0) {
                // See EntityFireworkRocket
                motion = ElytraLaunchPlanner.boost(motion, lookDirection);
            }
        }

        return displacement;
    }

    private boolean isPassableVolume(AABB box, boolean ignoreLava) {
        int xmax = fastCeil(box.maxX);
        int ymax = fastCeil(box.maxY);
        int zmax = fastCeil(box.maxZ);
        for (int x = fastFloor(box.minX); x < xmax; x++) {
            for (int y = fastFloor(box.minY); y < ymax; y++) {
                for (int z = fastFloor(box.minZ); z < zmax; z++) {
                    if (!this.passable(x, y, z, ignoreLava)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean passable(int x, int y, int z, boolean ignoreLava) {
        if (ignoreLava) {
            final BlockState state = this.bsi.get0(x, y, z);
            return state.getBlock() instanceof AirBlock || MovementHelper.isLava(state);
        } else {
            return !this.boi.get0(x, y, z);
        }
    }

    private int hotbarIndex(int slot) {
        return Math.clamp(slot, 1, 9) - 1;
    }

    private void refillHotbar() {
        if (this.refillCooldown > 0) {
            this.refillCooldown--;
            return;
        }
        if (!Baritone.settings().elytraRefillHotbar.value || !this.invTransactionQueue.isEmpty()
                || this.invTickCountdown > 0 || ctx.player().isUsingItem()
                || ctx.player().containerMenu != ctx.player().inventoryMenu
                || !ctx.player().inventoryMenu.getCarried().isEmpty()
                || (ctx.minecraft().screen != null && !(ctx.minecraft().screen instanceof ChatScreen))) {
            return;
        }
        int rocketSlot = hotbarIndex(Baritone.settings().elytraRocketSlot.value);
        int foodSlot = hotbarIndex(Baritone.settings().elytraFoodSlot.value);
        if (!this.process.repairOwnsHotbarSlot(rocketSlot) && refillSlot(rocketSlot, true)
                || foodSlot != rocketSlot && !this.process.repairOwnsHotbarSlot(foodSlot) && refillSlot(foodSlot, false)) {
            this.refillCooldown = Math.max(4, Baritone.settings().ticksBetweenInventoryMoves.value);
        }
    }

    private boolean refillSlot(int target, boolean rocket) {
        NonNullList<ItemStack> inventory = ctx.player().getInventory().getNonEquipmentItems();
        if (!inventory.get(target).isEmpty()) {
            return false;
        }
        int source = -1;
        int bestScore = 0;
        for (int i = 9; i < 36; i++) {
            ItemStack item = inventory.get(i);
            boolean matches = rocket ? isFireworks(item) : item.has(DataComponents.FOOD) && item.has(DataComponents.CONSUMABLE);
            if (!matches || item.isEmpty()) {
                continue;
            }
            int score = item.getCount();
            if (rocket && getFireworkBoost(item).orElse(0) == 3) {
                score += 128;
            }
            if (score > bestScore) {
                bestScore = score;
                source = i;
            }
        }
        if (source < 0) {
            return false;
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, source, target,
                ClickType.SWAP, ctx.player());
        return true;
    }

    private void tickInventoryTransactions() {
        if (invTickCountdown <= 0) {
            Runnable r = invTransactionQueue.poll();
            if (r != null) {
                r.run();
                invTickCountdown = Baritone.settings().ticksBetweenInventoryMoves.value;
            }
        }
        if (invTickCountdown > 0) invTickCountdown--;
    }

    private void queueWindowClick(int windowId, int slotId, int button, ClickType type) {
        invTransactionQueue.add(() -> ctx.playerController().windowClick(windowId, slotId, button, type, ctx.player()));
    }

    private int findGoodElytra() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            ItemStack slot = invy.get(i);
            if (slot.getItem() == Items.ELYTRA && (slot.getMaxDamage() - slot.getDamageValue()) > Baritone.settings().elytraMinimumDurability.value) {
                return i;
            }
        }
        return -1;
    }

    public boolean canReplaceElytra() {
        var player = ctx.player();
        return Baritone.settings().elytraAutoSwap.value
                && player.containerMenu == player.inventoryMenu
                && (!invTransactionQueue.isEmpty() || player.inventoryMenu.getCarried().isEmpty() && findGoodElytra() >= 0);
    }

    public boolean trySwapElytra() {
        if (!Baritone.settings().elytraAutoSwap.value) {
            return false;
        }
        if (!invTransactionQueue.isEmpty()) {
            return true;
        }

        var player = ctx.player();
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()
                || chest.getItem() != Items.ELYTRA
                || chest.getMaxDamage() - chest.getDamageValue() > Baritone.settings().elytraMinimumDurability.value) {
            return false;
        }

        int goodElytraSlot = findGoodElytra();
        if (goodElytraSlot < 0) {
            return false;
        }
        final int CHEST_SLOT = 6;
        final int slotId = goodElytraSlot < 9 ? goodElytraSlot + 36 : goodElytraSlot;
        queueWindowClick(player.inventoryMenu.containerId, slotId, 0, ClickType.PICKUP);
        queueWindowClick(player.inventoryMenu.containerId, CHEST_SLOT, 0, ClickType.PICKUP);
        queueWindowClick(player.inventoryMenu.containerId, slotId, 0, ClickType.PICKUP);
        return true;
    }

    void logVerbose(String message) {
        if (Baritone.settings().elytraChatSpam.value) {
            logDebug(message);
        }
    }
}
