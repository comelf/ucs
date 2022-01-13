/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package git.comelf.event.service;

import git.comelf.conf.Configuration;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface Service extends Cloneable {
    /**
     * Service states
     */
    enum STATE {
        /**
         * Constructed but not initialized
         */
        NOTINITED(0, "NOTINITED"),

        /**
         * Initialized but not started or stopped
         */
        INITED(1, "INITED"),

        /**
         * started and not stopped
         */
        STARTED(2, "STARTED"),

        /**
         * stopped. No further state transitions are permitted
         */
        STOPPED(3, "STOPPED");

        /**
         * An integer value for use in array lookup and JMX interfaces.
         * Although {@link Enum#ordinal()} could do this, explicitly
         * identify the numbers gives more stability guarantees over time.
         */
        private final int value;

        /**
         * A name of the state that can be used in messages
         */
        private final String statename;

        private STATE(int value, String name) {
            this.value = value;
            this.statename = name;
        }

        /**
         * Get the integer value of a state
         *
         * @return the numeric value of the state
         */
        public int getValue() {
            return value;
        }

        /**
         * Get the name of a state
         *
         * @return the state's name
         */
        @Override
        public String toString() {
            return statename;
        }
    }

    void init(Configuration conf);

    void start();

    void stop();

    void close() throws IOException;


    void registerServiceListener(ServiceStateChangeListener listener);

    void unregisterServiceListener(ServiceStateChangeListener listener);

    String getName();

    Configuration getConfig();

    STATE getServiceState();

    long getStartTime();

    boolean isInState(STATE state);

    boolean waitForServiceToStop(long timeout);

    List<LifecycleEvent> getLifecycleHistory();

    Map<String, String> getBlockers();
}
