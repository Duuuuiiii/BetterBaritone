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
import java.util.concurrent.TimeUnit;

public final class ElytraFlightStats {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private final Deque<Sample> samples = new ArrayDeque<>();
    private Sample profileAnchor;
    private int rocketsUsed;
    private int durabilityUsed;
    private int previousDurability = -1;
    private double displayedEta = -1;
    private long etaTime;

    public void tick(double distance, int durability) {
        tick(System.nanoTime(), distance, durability);
    }

    void tick(long now, double distance, int durability) {
        if (durability >= 0 && this.previousDurability > durability) {
            int loss = this.previousDurability - durability;
            if (loss <= 4) {
                this.durabilityUsed += loss;
            }
        }
        this.previousDurability = durability;
        Sample last = this.samples.peekLast();
        if (last == null || now - last.time >= SECOND) {
            Sample current = new Sample(now, distance, this.rocketsUsed, this.durabilityUsed);
            this.samples.addLast(current);
            if (this.profileAnchor == null) {
                this.profileAnchor = current;
            }
            while (now - this.samples.getFirst().time > 60 * SECOND) {
                this.samples.removeFirst();
            }
        }
    }

    public void recordRocket() {
        this.rocketsUsed++;
    }

    public Estimate estimate(double distance) {
        Sample first = this.samples.peekFirst();
        Sample last = this.samples.peekLast();
        if (first == null || last == null || last.time - first.time < 10 * SECOND) {
            return null;
        }
        double progress = first.distance - last.distance;
        if (progress < 16) {
            return null;
        }
        double factor = Math.max(0, distance) / progress;
        return new Estimate(
                Math.round((last.time - first.time) / (double) SECOND * factor),
                last.rockets == first.rockets ? -1 : (long) Math.ceil((last.rockets - first.rockets) * factor),
                last.durability == first.durability ? -1 : (long) Math.ceil((last.durability - first.durability) * factor));
    }

    public long smoothEta(long live, long historical) {
        return smoothEta(System.nanoTime(), live, historical);
    }

    long smoothEta(long now, long live, long historical) {
        double target = live >= 0 ? live : historical;
        if (live >= 0 && historical >= 0) {
            target = historical * 0.8 + Math.clamp((double) live, historical * 0.55, historical * 1.8) * 0.2;
        }
        if (target < 0) {
            return -1;
        }
        double elapsed = (now - this.etaTime) / (double) SECOND;
        if (this.displayedEta < 0) {
            this.displayedEta = target;
            this.etaTime = now;
        } else if (elapsed >= 1) {
            double countdown = Math.max(0, this.displayedEta - elapsed);
            this.displayedEta = countdown + (target - countdown) * (1 - Math.exp(-elapsed / 35));
            this.etaTime = now;
        }
        return Math.round(this.displayedEta);
    }

    public ElytraFlightProfile takeProfileSample(boolean finishing) {
        Sample last = this.samples.peekLast();
        if (this.profileAnchor == null || last == null) {
            return null;
        }
        long elapsed = last.time - this.profileAnchor.time;
        if (elapsed < (finishing ? 30 : 60) * SECOND) {
            return null;
        }
        double blocks = this.profileAnchor.distance - last.distance;
        if (blocks < 128) {
            return null;
        }
        ElytraFlightProfile sample = new ElytraFlightProfile(
                Math.clamp(60 * SECOND * blocks / elapsed, 1, 100_000),
                Math.min(10_000, 1000 * (last.rockets - this.profileAnchor.rockets) / blocks),
                Math.min(20_000, 1000 * (last.durability - this.profileAnchor.durability) / blocks));
        this.profileAnchor = last;
        return sample;
    }

    private record Sample(long time, double distance, int rockets, int durability) {}

    public record Estimate(long seconds, long rockets, long durability) {}
}
