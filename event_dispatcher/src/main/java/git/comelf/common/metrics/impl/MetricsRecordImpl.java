package git.comelf.common.metrics.impl;

import git.comelf.common.metrics.AbstractMetric;
import git.comelf.common.metrics.MetricsInfo;
import git.comelf.common.metrics.MetricsTag;

import java.util.List;
import java.util.Objects;

public class MetricsRecordImpl extends AbstractMetricsRecord {
    protected static final String DEFAULT_CONTEXT = "default";

    private final long timestamp;
    private final MetricsInfo info;
    private final List<MetricsTag> tags;
    private final Iterable<AbstractMetric> metrics;

    /**
     * Construct a metrics record
     *
     * @param info      {@link MetricsInfo} of the record
     * @param timestamp of the record
     * @param tags      of the record
     * @param metrics   of the record
     */
    public MetricsRecordImpl(MetricsInfo info, long timestamp,
                             List<MetricsTag> tags,
                             Iterable<AbstractMetric> metrics) {
        Objects.requireNonNull(info);
        Objects.requireNonNull(tags);
        Objects.requireNonNull(metrics);

        this.timestamp = timestamp;  // checkArg(timestamp, timestamp > 0, "timestamp");
        this.info = info; //checkNotNull(info, "info");
        this.tags = tags; //checkNotNull(tags, "tags");
        this.metrics = metrics; // checkNotNull(metrics, "metrics");
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    @Override
    public String name() {
        return info.name();
    }

    MetricsInfo info() {
        return info;
    }

    @Override
    public String description() {
        return info.description();
    }

    @Override
    public String context() {
        // usually the first tag
        for (MetricsTag t : tags) {
            if (t.info() == MsInfo.Context) {
                return t.value();
            }
        }
        return DEFAULT_CONTEXT;
    }

    @Override
    public List<MetricsTag> tags() {
        return tags; // already unmodifiable from MetricsRecordBuilderImpl#tags
    }

    @Override
    public Iterable<AbstractMetric> metrics() {
        return metrics;
    }
}
