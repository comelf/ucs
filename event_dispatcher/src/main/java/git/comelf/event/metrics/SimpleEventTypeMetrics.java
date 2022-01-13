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

package git.comelf.event.metrics;

import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleEventTypeMetrics<T extends Enum<T>> implements EventTypeMetrics<T> {

    private final EnumMap<T, AtomicLong> eventCountMetrics;
    private final EnumMap<T, AtomicLong> processingTimeMetrics;
    private final Class<T> enumClass;

    public SimpleEventTypeMetrics(Class<T> enumClass) {
        Objects.requireNonNull(enumClass);

        this.enumClass = enumClass;
        this.eventCountMetrics = new EnumMap<>(this.enumClass);
        this.processingTimeMetrics = new EnumMap<>(this.enumClass);

        //Initialize enum
        final T[] enums = enumClass.getEnumConstants();
        for (final T type : enums) {
            eventCountMetrics.put(type, new AtomicLong(0L));
            processingTimeMetrics.put(type, new AtomicLong(0L));
        }
    }


    @Override
    public void increment(T type, long processingTimeUs) {
        if (eventCountMetrics.get(type) != null) {
            eventCountMetrics.get(type).incrementAndGet();
            processingTimeMetrics.get(type).addAndGet(processingTimeUs);
        }
    }

    @Override
    public long get(T type) {
        return eventCountMetrics.get(type).get();
    }

    public long getTotalProcessingTime(T type) {
        return processingTimeMetrics.get(type).get();
    }

    public Class<T> getEnumClass() {
        return enumClass;
    }
}
