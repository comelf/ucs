package git.comelf.common.metrics;

public interface MetricsSink extends MetricsPlugin {
    /**
     * Put a metrics record in the sink
     *
     * @param record the record to put
     */
    void putMetrics(MetricsRecord record);

    /**
     * Flush any buffered metrics
     */
    void flush();
}
