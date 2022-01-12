package git.comelf.common.metrics;

public abstract class MetricsFilter implements MetricsPlugin {

    /**
     * Whether to accept the name
     * @param name  to filter on
     * @return  true to accept; false otherwise.
     */
    public abstract boolean accepts(String name);

    /**
     * Whether to accept the tag
     * @param tag to filter on
     * @return  true to accept; false otherwise
     */
    public abstract boolean accepts(MetricsTag tag);

    /**
     * Whether to accept the tags
     * @param tags to filter on
     * @return  true to accept; false otherwise
     */
    public abstract boolean accepts(Iterable<MetricsTag> tags);

    /**
     * Whether to accept the record
     * @param record  to filter on
     * @return  true to accept; false otherwise.
     */
    public boolean accepts(MetricsRecord record) {
        return accepts(record.name()) && accepts(record.tags());
    }
}
