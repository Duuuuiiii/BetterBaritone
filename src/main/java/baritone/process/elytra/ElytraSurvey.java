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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalXZ;
import baritone.cache.WorldData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

import static baritone.process.elytra.ElytraUi.text;

final class ElytraSurvey {
    static final ElytraSurvey INSTANCE = new ElytraSurvey();
    private Session session;
    private String message;

    private ElytraSurvey() {}

    ElytraSurveyPlan.Config lastConfig(Minecraft client, IBaritone baritone) {
        if (session != null && session.level == client.level) {
            return session.config;
        }
        Path journal = surveyFile(client, baritone);
        try {
            return journal == null ? null : ElytraSurveyHistory.loadConfig(journal);
        } catch (IOException e) {
            message = historyError(e);
            return null;
        }
    }

    String status(Minecraft client) {
        if (session == null || session.level != client.level) {
            return message;
        }
        var flight = flight();
        String state = flight != null && flight.isFlightPaused() ? text("已暂停", "Paused")
                : session.finalLeg ? text("最后航段", "Final leg") : text("目标", "Target");
        return state + "  " + session.target.x() + ", " + session.target.z() + "  ·  "
                + text("已完成 ", "Reached ") + session.reached;
    }

    String start(Minecraft client, IBaritone baritone, ElytraSurveyPlan.Config config) {
        if (client.player == null || client.level == null || client.level.dimension() != Level.NETHER) {
            return text("进入地狱后才能探索", "Enter the Nether to explore");
        }
        if (session != null) {
            return text("请先结束当前探索", "End the current survey first");
        }
        Path journal = surveyFile(client, baritone);
        if (journal == null || !baritone.getElytraProcess().isLoaded()
                || !(baritone.getElytraProcess() instanceof ElytraPanelFlight flight)) {
            return text("当前世界的探索功能不可用", "Exploration is unavailable in this world");
        }
        var position = point(client.player.blockPosition());
        Session next;
        try {
            next = new Session(client.level, config, new ElytraSurveyHistory(journal));
            next.history.mark(position);
            next.target = nextWaypoint(next, position);
            if (next.target == null) {
                return text("范围内没有符合条件的新目标", "No new waypoint matches these settings");
            }
            next.history.flush();
            next.history.saveConfig(config);
        } catch (IOException e) {
            return historyError(e);
        }

        GoalXZ goal = goal(next.target);
        boolean alreadyFlying = flight.isActive();
        baritone.getCustomGoalProcess().setGoal(goal);
        if (!alreadyFlying) {
            baritone.getCommandManager().execute("elytra");
        }
        if (!flight.isActive() || !matches(flight.flightDestination(), next.target)) {
            return text("无法开始飞行", "Could not start flight");
        }
        session = next;
        message = null;
        return null;
    }

    void tick(Minecraft client) {
        Session current = session;
        if (current == null) {
            return;
        }
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        var process = baritone.getElytraProcess();
        if (client.player == null || current.level != client.level || !(process instanceof ElytraPanelFlight flight)
                || !matches(flight.flightDestination(), current.target) || !flight.isActive() && !flight.isFlightPaused()) {
            finish(current.finalLeg && current.targetReached
                    ? text("探索目标已完成", "Exploration goals complete") : text("探索已结束", "Exploration ended"));
            return;
        }
        if (flight.isFlightPaused()) {
            return;
        }
        var position = point(client.player.blockPosition());
        if (client.player.isFallFlying()) {
            current.history.mark(position);
        }
        try {
            if (client.player.tickCount % 100 == 0) {
                current.history.flush();
            }
            if (!matches(process.currentDestination(), current.target) || !client.player.isFallFlying()
                    || flight.remainingDistance() > current.config.switchDistance()) {
                return;
            }
            if (!current.targetReached) {
                current.history.mark(current.target);
                current.history.flush();
                current.targetReached = true;
                current.reached++;
            }
            if (current.finalLeg) {
                return;
            }
            var target = current.config.limit() > 0 && current.reached >= current.config.limit()
                    ? null : nextWaypoint(current, position);
            if (target == null) {
                current.finalLeg = true;
                return;
            }
            process.pathTo(goal(target));
            if (!matches(flight.flightDestination(), target) || !flight.isActive()) {
                finish(text("无法继续探索", "Could not continue exploring"));
                return;
            }
            current.target = target;
            current.targetReached = false;
        } catch (IOException e) {
            session = null;
            message = historyError(e);
        }
    }

    boolean isActive() {
        return session != null;
    }

    void stop() {
        if (session == null) {
            return;
        }
        var process = BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess();
        if (process instanceof ElytraPanelFlight flight && matches(flight.flightDestination(), session.target)) {
            if (flight.isFlightPaused()) {
                process.onLostControl();
            } else {
                flight.takeOver();
            }
        }
        finish(text("探索已停止", "Exploration stopped"));
    }

    void finish(String status) {
        Session current = session;
        session = null;
        message = status;
        if (current != null) {
            try {
                current.history.flush();
            } catch (IOException e) {
                message = historyError(e);
            }
        }
    }

    private static Path surveyFile(Minecraft client, IBaritone baritone) {
        if (client.player == null || client.level == null || client.level.dimension() != Level.NETHER
                || !(baritone.getWorldProvider().getCurrentWorld() instanceof WorldData world)) {
            return null;
        }
        return world.directory.resolve("elytra-survey-" + client.player.getUUID() + ".log");
    }

    private static String historyError(IOException e) {
        LoggerFactory.getLogger("BetterBaritone").warn("Could not access elytra survey history", e);
        return text("探索记录读写失败，详情见游戏日志", "Could not access survey history. See the game log.");
    }

    private static ElytraPanelFlight flight() {
        return BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess() instanceof ElytraPanelFlight flight
                ? flight : null;
    }

    private static ElytraSurveyPlan.Point nextWaypoint(Session current, ElytraSurveyPlan.Point position) {
        return current.plan.next(position, candidate -> !current.history.contains(candidate)
                && current.level.getWorldBorder().isWithinBounds(new BlockPos(candidate.x(), 64, candidate.z())));
    }

    private static boolean matches(BlockPos target, ElytraSurveyPlan.Point point) {
        return target != null && target.getX() == point.x() && target.getZ() == point.z();
    }

    private static ElytraSurveyPlan.Point point(BlockPos pos) {
        return new ElytraSurveyPlan.Point(pos.getX(), pos.getZ());
    }

    private static GoalXZ goal(ElytraSurveyPlan.Point point) {
        return new GoalXZ(point.x(), point.z());
    }

    private static final class Session {
        final ClientLevel level;
        final ElytraSurveyPlan.Config config;
        final ElytraSurveyPlan plan;
        final ElytraSurveyHistory history;
        ElytraSurveyPlan.Point target;
        int reached;
        boolean targetReached;
        boolean finalLeg;

        Session(ClientLevel level, ElytraSurveyPlan.Config config, ElytraSurveyHistory history) {
            this.level = level;
            this.config = config;
            this.history = history;
            this.plan = new ElytraSurveyPlan(config);
        }
    }
}
