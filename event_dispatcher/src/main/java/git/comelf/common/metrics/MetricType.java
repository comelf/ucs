package git.comelf.common.metrics;

public enum MetricType {
    /**
     * A monotonically increasing metric that can be used
     * to calculate throughput
     */
    COUNTER,

    /**
     * An arbitrary varying metric
     */
    GAUGE
}
