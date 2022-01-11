package git.comelf.common.metrics.lib;

import git.comelf.common.metrics.MetricsInfo;

import java.util.Objects;

public abstract class MutableGauge extends MutableMetric {
    private final MetricsInfo info;

    protected MutableGauge(MetricsInfo info) {
        Objects.requireNonNull(info, "metric info cannot be null");
        this.info = info;
    }

    protected MetricsInfo info() {
        return info;
    }

    /**
     * Increment the value of the metric by 1
     */
    public abstract void incr();

    /**
     * Decrement the value of the metric by 1
     */
    public abstract void decr();
}