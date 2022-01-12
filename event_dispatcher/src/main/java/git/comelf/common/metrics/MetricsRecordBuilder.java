package git.comelf.common.metrics;

public abstract class MetricsRecordBuilder {
    /**
     * Add a metrics value with metrics information
     *
     * @param info  metadata of the tag
     * @param value of the tag
     * @return self
     */
    public abstract MetricsRecordBuilder tag(MetricsInfo info, String value);

    /**
     * Add an immutable metrics tag object
     *
     * @param tag a pre-made tag object (potentially save an object construction)
     * @return self
     */
    public abstract MetricsRecordBuilder add(MetricsTag tag);

    /**
     * Add a pre-made immutable metric object
     *
     * @param metric the pre-made metric to save an object construction
     * @return self
     */
    public abstract MetricsRecordBuilder add(AbstractMetric metric);

    /**
     * Set the context tag
     *
     * @param value of the context
     * @return self
     */
    public abstract MetricsRecordBuilder setContext(String value);

    /**
     * Add an integer metric
     *
     * @param info  metadata of the metric
     * @param value of the metric
     * @return self
     */
    public abstract MetricsRecordBuilder addCounter(MetricsInfo info, int value);

    /**
     * Add an long metric
     *
     * @param info  metadata of the metric
     * @param value of the metric
     * @return self
     */
    public abstract MetricsRecordBuilder addCounter(MetricsInfo info, long value);

    /**
     * Add a integer gauge metric
     *
     * @param info  metadata of the metric
     * @param value of the metric
     * @return self
     */
    public abstract MetricsRecordBuilder addGauge(MetricsInfo info, int value);

    /**
     * Add a long gauge metric
     *
     * @param info  metadata of the metric
     * @param value of the metric
     * @return self
     */
    public abstract MetricsRecordBuilder addGauge(MetricsInfo info, long value);

    /**
     * Add a float gauge metric
     *
     * @param info  metadata of the metric
     * @param value of the metric
     * @return self
     */
//    public abstract MetricsRecordBuilder addGauge(MetricsInfo info, float value);

    /**
     * Add a double gauge metric
     *
     * @param info  metadata of the metric
     * @param value of the metric
     * @return self
     */
//    public abstract MetricsRecordBuilder addGauge(MetricsInfo info, double value);

    /**
     * @return the parent metrics collector object
     */
    public abstract MetricsCollector parent();

    /**
     * Syntactic sugar to add multiple records in a collector in a one liner.
     *
     * @return the parent metrics collector object
     */
    public MetricsCollector endRecord() {
        return parent();
    }
}
