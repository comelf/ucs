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
