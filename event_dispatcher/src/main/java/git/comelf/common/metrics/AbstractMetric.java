package git.comelf.common.metrics;

import java.util.Objects;

public abstract class AbstractMetric implements MetricsInfo {
    private final MetricsInfo info;

    /**
     * Construct the metric
     *
     * @param info about the metric
     */
    protected AbstractMetric(MetricsInfo info) {
        Objects.requireNonNull(info, "metric info cannot be null");
        this.info = info;
    }

    @Override
    public String name() {
        return info.name();
    }

    @Override
    public String description() {
        return info.description();
    }

    protected MetricsInfo info() {
        return info;
    }

    /**
     * Get the value of the metric
     *
     * @return the value of the metric
     */
    public abstract Number value();

    /**
     * Get the type of the metric
     *
     * @return the type of the metric
     */
    public abstract MetricType type();

    /**
     * Accept a visitor interface
     *
     * @param visitor of the metric
     */
    public abstract void visit(MetricsVisitor visitor);

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof AbstractMetric) {
            final AbstractMetric other = (AbstractMetric) obj;
            return Objects.equals(info, other.info()) &&
                    Objects.equals(value(), other.value());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(info, value());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(this.getClass().getSimpleName());
        sb.append("}");
        sb.append(" info=");
        sb.append(info);
        sb.append(", value=");
        sb.append(value());
        return sb.toString();
    }
}
