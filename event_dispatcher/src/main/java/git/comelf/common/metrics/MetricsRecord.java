package git.comelf.common.metrics;

import java.util.Collection;

public interface MetricsRecord {
    /**
     * Get the timestamp of the metrics
     *
     * @return the timestamp
     */
    long timestamp();

    /**
     * @return the metrics record name
     */
    String name();

    /**
     * @return the description of the metrics record
     */
    String description();

    /**
     * @return the context name of the metrics record
     */
    String context();

    /**
     * Get the tags of the record
     * Note: returning a collection instead of iterable as we
     * need to use tags as keys (hence Collection#hashCode etc.) in maps
     *
     * @return an unmodifiable collection of tags
     */
    Collection<MetricsTag> tags();

    /**
     * Get the metrics of the record
     *
     * @return an immutable iterable interface for metrics
     */
    Iterable<AbstractMetric> metrics();
}
