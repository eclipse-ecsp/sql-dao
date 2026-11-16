/*
 *
 *
 *   ******************************************************************************
 *
 *    Copyright (c) 2023-24 Harman International
 *
 *
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *
 *    you may not use this file except in compliance with the License.
 *
 *    You may obtain a copy of the License at
 *
 *
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *    Unless required by applicable law or agreed to in writing, software
 *
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *    See the License for the specific language governing permissions and
 *
 *    limitations under the License.
 *
 *
 *
 *    SPDX-License-Identifier: Apache-2.0
 *
 *    *******************************************************************************
 *
 *
 */

package org.eclipse.ecsp.sql.migration;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records readiness changes. It is registered through a test's context configuration and is deliberately not a
 * component, so that the component scan of the other tests does not pick it up.
 */
class ReadinessRecorder {

    private final List<ReadinessState> states = new CopyOnWriteArrayList<>();

    @EventListener
    void onReadinessChange(AvailabilityChangeEvent<ReadinessState> event) {
        states.add(event.getState());
    }

    List<ReadinessState> states() {
        return List.copyOf(states);
    }
}
