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

import net.minecraft.core.BlockPos;

public interface ElytraPanelFlight {

    boolean isActive();
    boolean isFlightPaused();
    void toggleFlightPause();
    void takeOver();
    void requestLanding();
    BlockPos flightDestination();
    double remainingDistance();
    double distanceToArrival(BlockPos target, boolean horizontal);
    String flightStatus();
    ElytraFlightStats.Estimate flightEstimate();
    ElytraFlightStats.Estimate estimateForDistance(double distance);
    long flightRocketsUsed();
    ElytraMileage mileage();
}
