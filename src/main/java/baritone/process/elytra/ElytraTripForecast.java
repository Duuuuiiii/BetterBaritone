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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public final class ElytraTripForecast {
    private static final long UPDATE_NANOS = 5_000_000_000L;
    private static final long MAX_WINDOW_NANOS = 1_800_000_000_000L;
    private static final double MIN_PROGRESS = 512;
    private final Deque<Sample> recent = new ArrayDeque<>();
    private long elapsed;
    private long previousTime = Long.MIN_VALUE;
    private double previousRemaining;
    private double netProgress;
    private long rockets;
    private double secondsPerBlock;
    private double rocketsPerBlock = -1;
    private double observedWeight;

    public void sample(double remaining) {
        sample(remaining, System.nanoTime());
    }

    void sample(double remaining, long now) {
        if (previousTime != Long.MIN_VALUE) {
            long interval = now - previousTime;
            double progress = previousRemaining - remaining;
            if (interval > 0 && interval <= 3_000_000_000L && Math.abs(progress) < 20) {
                elapsed += interval;
                netProgress += progress;
            }
        }
        previousTime = now;
        previousRemaining = remaining;

        if (recent.isEmpty() || elapsed - recent.getLast().time >= UPDATE_NANOS) {
            updateRates();
        }
    }

    public void interrupt() {
        previousTime = Long.MIN_VALUE;
    }

    public void rocketUsed() {
        rockets++;
    }

    public long rocketsUsed() {
        return rockets;
    }

    public ElytraFlightStats.Estimate estimate(double remaining, ElytraFlightStats.Estimate fallback) {
        if (remaining <= 0) {
            return new ElytraFlightStats.Estimate(0, 0, 0);
        }
        if (observedWeight == 0) {
            return fallback;
        }
        long seconds = Math.round(fallback.seconds() * (1 - observedWeight) + secondsPerBlock * remaining * observedWeight);
        long expectedRockets = fallback.rockets();
        if (rocketsPerBlock >= 0) {
            expectedRockets = expectedRockets < 0 ? (long) Math.ceil(rocketsPerBlock * remaining)
                    : (long) Math.ceil(expectedRockets * (1 - observedWeight) + rocketsPerBlock * remaining * observedWeight);
        }
        return new ElytraFlightStats.Estimate(seconds, expectedRockets, fallback.durability());
    }

    private void updateRates() {
        recent.addLast(new Sample(elapsed, netProgress, rockets));
        while (recent.size() > 2) {
            Iterator<Sample> samples = recent.iterator();
            samples.next();
            Sample next = samples.next();
            if (netProgress - next.progress < ElytraMileage.ETA_BLOCKS && elapsed - next.time < MAX_WINDOW_NANOS) {
                break;
            }
            recent.removeFirst();
        }

        Sample oldest = recent.getFirst();
        double progress = netProgress - oldest.progress;
        long span = elapsed - oldest.time;
        if (progress < MIN_PROGRESS || span == 0) {
            return;
        }
        double measuredTime = span / 1e9 / progress;
        long used = rockets - oldest.rockets;
        double measuredRockets = used > 0 || progress >= ElytraMileage.ETA_BLOCKS ? used / progress : -1;
        double gain = observedWeight == 0 ? 1 : 0.08;
        secondsPerBlock += (measuredTime - secondsPerBlock) * gain;
        if (measuredRockets >= 0) {
            rocketsPerBlock = rocketsPerBlock < 0 || observedWeight == 0 ? measuredRockets
                    : rocketsPerBlock + (measuredRockets - rocketsPerBlock) * gain;
        }
        observedWeight = Math.min(1, progress / ElytraMileage.ETA_BLOCKS);
    }

    private record Sample(long time, double progress, long rockets) {}
}
