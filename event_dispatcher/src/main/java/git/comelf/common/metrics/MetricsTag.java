package git.comelf.common.metrics;

import java.util.Objects;

public class MetricsTag implements MetricsInfo {
    private final MetricsInfo info;
    private final String value;

    /**
     * Construct the tag with name, description and value
     *
     * @param info  of the tag
     * @param value of the tag
     */
    public MetricsTag(MetricsInfo info, String value) {
        Objects.requireNonNull(info, "tag info cannot be null");
        this.info = info;
        this.value = value;
    }

    @Override
    public String name() {
        return info.name();
    }

    @Override
    public String description() {
        return info.description();
    }

    /**
     * @return the info object of the tag
     */
    public MetricsInfo info() {
        return info;
    }

    /**
     * Get the value of the tag
     *
     * @return the value
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof MetricsTag) {
            final MetricsTag other = (MetricsTag) obj;
            return Objects.equals(info, other.info()) &&
                    Objects.equals(value, other.value());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(info, value);
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
        sb.append(value);
        return sb.toString();
    }
}
