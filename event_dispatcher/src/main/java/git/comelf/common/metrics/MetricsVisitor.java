package git.comelf.common.metrics;

public interface MetricsVisitor {
    /**
     * Callback for integer value gauges
     * @param info  the metric info
     * @param value of the metric
     */
    public void gauge(MetricsInfo info, int value);

    /**
     * Callback for long value gauges
     * @param info  the metric info
     * @param value of the metric
     */
    public void gauge(MetricsInfo info, long value);

    /**
     * Callback for float value gauges
     * @param info  the metric info
     * @param value of the metric
     */
    public void gauge(MetricsInfo info, float value);

    /**
     * Callback for double value gauges
     * @param info  the metric info
     * @param value of the metric
     */
    public void gauge(MetricsInfo info, double value);

    /**
     * Callback for integer value counters
     * @param info  the metric info
     * @param value of the metric
     */
    public void counter(MetricsInfo info, int value);

    /**
     * Callback for long value counters
     * @param info  the metric info
     * @param value of the metric
     */
    public void counter(MetricsInfo info, long value);
}
