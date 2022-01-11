package git.comelf.event.metrics;

import git.comelf.common.metrics.MetricsSource;

public interface EventTypeMetrics <T extends Enum<T>> extends MetricsSource {

    void increment(T type, long processingTimeUs);

    long get(T type);

}
