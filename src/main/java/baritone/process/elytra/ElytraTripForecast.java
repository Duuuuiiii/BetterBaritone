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
    private static final double MIN_PROGRESS = 512;
    private final Deque<Sample> recent = new ArrayDeque<>();
    private long elapsed;
    private long previousTime = Long.MIN_VALUE;
    private double previousRemaining;
    private double netProgress;
    private long rockets;
    private long wear;
    private double secondsPerBlock;
    private double rocketsPerBlock = -1;
    private double wearPerBlock = -1;
    private double observedWeight;

    public void sample(double remaining) {
        sample(remaining, System.nanoTime());
    }

    void sample(double remaining, long now) {
        if (previousTime != Long.MIN_VALUE) {
            long interval = now - previousTime;
            double progress = previousRemaining - remaining;
            if (interval > 0) {
                elapsed += interval;
                // Position corrections do not contribute distance, but the active trip still took that time.
                if (Math.abs(progress) < 20) {
                    netProgress += progress;
                }
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

    public void recordWear(int amount) {
        wear += amount;
    }

    public long wearUsed() {
        return wear;
    }

    public ElytraFlightStats.Estimate estimate(double remaining, ElytraFlightStats.Estimate fallback) {
        if (remaining <= 0) {
            return new ElytraFlightStats.Estimate(0, 0, 0);
        }
        if (observedWeight == 0) {
            return fallback;
        }
        long seconds = Math.round(fallback.seconds() * (1 - observedWeight) + secondsPerBlock * remaining * observedWeight);
        return new ElytraFlightStats.Estimate(seconds,
                estimateConsumption(remaining, fallback.rockets(), rocketsPerBlock),
                estimateConsumption(remaining, fallback.durability(), wearPerBlock));
    }

    private long estimateConsumption(double remaining, long historical, double measuredRate) {
        if (measuredRate < 0) {
            return historical;
        }
        double measured = measuredRate * remaining;
        return (long) Math.ceil(historical < 0 ? measured : historical * (1 - observedWeight) + measured * observedWeight);
    }

    double calibratedSecondsPerTenThousand() {
        return observedWeight == 1 ? secondsPerBlock * ElytraMileage.ETA_BLOCKS : 0;
    }

    double calibratedRocketsPerTenThousand() {
        return observedWeight == 1 && rocketsPerBlock >= 0 ? rocketsPerBlock * ElytraMileage.ETA_BLOCKS : -1;
    }

    double calibratedWearPerTenThousand() {
        return observedWeight == 1 && wearPerBlock >= 0 ? wearPerBlock * ElytraMileage.ETA_BLOCKS : -1;
    }

    private void updateRates() {
        recent.addLast(new Sample(elapsed, netProgress, rockets, wear));
        while (recent.size() > 2) {
            Iterator<Sample> samples = recent.iterator();
            samples.next();
            Sample next = samples.next();
            if (netProgress - next.progress < ElytraMileage.ETA_BLOCKS) {
                break;
            }
            recent.removeFirst();
        }

        Sample oldest = recent.getFirst();
        double progress = netProgress - oldest.progress;
        double span = elapsed - oldest.time;
        if (progress < MIN_PROGRESS || span <= 0) {
            return;
        }
        double used = rockets - oldest.rockets;
        double wearUsed = wear - oldest.wear;
        if (progress > ElytraMileage.ETA_BLOCKS) {
            // The two oldest samples bracket the start of the last 10,000 blocks of net progress.
            Iterator<Sample> samples = recent.iterator();
            samples.next();
            Sample next = samples.next();
            double trim = (progress - ElytraMileage.ETA_BLOCKS) / (next.progress - oldest.progress);
            span -= (next.time - oldest.time) * trim;
            used -= (next.rockets - oldest.rockets) * trim;
            wearUsed -= (next.wear - oldest.wear) * trim;
            progress = ElytraMileage.ETA_BLOCKS;
        }
        double measuredTime = span / 1e9 / progress;
        double gain = observedWeight == 0 ? 1 : 0.08;
        secondsPerBlock += (measuredTime - secondsPerBlock) * gain;
        double weight = Math.min(1, progress / ElytraMileage.ETA_BLOCKS);
        // Switch to the complete window once it is available, then smooth subsequent updates.
        double consumptionGain = observedWeight < 1 && weight == 1 ? 1 : gain;
        rocketsPerBlock = updateConsumptionRate(rocketsPerBlock, used, progress, consumptionGain);
        wearPerBlock = updateConsumptionRate(wearPerBlock, wearUsed, progress, consumptionGain);
        observedWeight = weight;
    }

    private static double updateConsumptionRate(double previous, double used, double progress, double gain) {
        if (used == 0 && progress < ElytraMileage.ETA_BLOCKS) {
            return previous;
        }
        double measured = used / progress;
        return previous < 0 ? measured : previous + (measured - previous) * gain;
    }

    private record Sample(long time, double progress, long rockets, long wear) {}
}
