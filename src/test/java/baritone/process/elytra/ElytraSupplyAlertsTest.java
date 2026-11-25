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

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ElytraSupplyAlertsTest {
    @Test
    public void lowStockRemainsOneAlertUntilThePlayerActuallyRestocks() {
        int low = ElytraSupplyAlerts.elytraAlertLevel(40, 0);
        assertEquals(1, low);
        assertEquals(low, ElytraSupplyAlerts.elytraAlertLevel(41, low));
        assertEquals(low, ElytraSupplyAlerts.elytraAlertLevel(39, low));
        int urgent = ElytraSupplyAlerts.elytraAlertLevel(20, low);
        assertEquals(2, urgent);
        assertEquals(urgent, ElytraSupplyAlerts.elytraAlertLevel(23, urgent));
        assertEquals(0, ElytraSupplyAlerts.elytraAlertLevel(64, urgent));
    }
}
