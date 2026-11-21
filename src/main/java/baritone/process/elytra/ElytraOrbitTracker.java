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

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

public final class ElytraOrbitTracker {
    private final ArrayDeque<Sample> trail = new ArrayDeque<>();
    private double distance;

    public boolean sample(int tick, Vec3 position) {
        if (!this.trail.isEmpty()) {
            this.distance += position.subtract(this.trail.getLast().position).horizontalDistance();
        }
        while (!this.trail.isEmpty() && tick - this.trail.getFirst().tick > 480) {
            this.trail.removeFirst();
        }
        boolean loop = false;
        for (Sample sample : this.trail) {
            if (tick - sample.tick >= 60 && this.distance - sample.distance >= 45
                    && Math.abs(position.y - sample.position.y) < 8
                    && position.subtract(sample.position).horizontalDistanceSqr() <= 49) {
                loop = true;
                break;
            }
        }
        this.trail.addLast(new Sample(tick, this.distance, position));
        return loop;
    }

    public void clear() {
        this.trail.clear();
        this.distance = 0;
    }

    private record Sample(int tick, double distance, Vec3 position) {}
}
