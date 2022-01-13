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

package git.comelf.conf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Properties;

public class Configuration {

    public static final String DISPATCHER_PRINT_EVENTS_INFO_THRESHOLD = "dispatcher.print-events-info.threshold";
    public static final int DEFAULT_DISPATCHER_PRINT_EVENTS_INFO_THRESHOLD = 5000;
    public static final String DISPATCHER_DRAIN_EVENTS_TIMEOUT = "dispatcher.drain-events.timeout";
    public static final long DEFAULT_DISPATCHER_DRAIN_EVENTS_TIMEOUT = 300_000;

    private static final Logger LOG = LoggerFactory.getLogger(Configuration.class);

    private Properties properties;

    public Configuration() {
    }

    protected synchronized Properties getProps() {
        if (properties == null) {
            properties = new Properties();
        }
        return properties;
    }

    public String get(String name) {
        return getProps().getProperty(name);
    }

    public void setInt(String name, int value) {
        set(name, Integer.toString(value));
    }

    public void setLong(String name, long value) {
        set(name, Long.toString(value));
    }

    public void set(String name, String value) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(value);
        name = name.trim();
        getProps().setProperty(name, value);
    }

    public int getInt(String name, int defaultValue) {
        String valueString = getTrimmed(name);
        if (valueString == null)
            return defaultValue;
        try {
            return Integer.parseInt(valueString);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    public long getLong(String name, long defaultValue) {
        String valueString = getTrimmed(name);
        if (valueString == null)
            return defaultValue;
        try {
            return Long.parseLong(valueString);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }


    public String getTrimmed(String name) {
        String value = get(name);
        if (null == value) {
            return null;
        } else {
            return value.trim();
        }
    }

}
