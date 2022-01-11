package git.comelf.common.metrics.lib;

import git.comelf.common.metrics.MetricsInfo;

import java.util.Objects;

public class MetricsInfoImpl implements MetricsInfo {

    private final String name, description;

    MetricsInfoImpl(String name, String description) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        this.name = name;
        this.description = description;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof MetricsInfo) {
            MetricsInfo other = (MetricsInfo) obj;
            return Objects.equals(name, other.name()) &&
                    Objects.equals(description, other.description());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(this.getClass().getSimpleName());
        sb.append("}");
        sb.append(" name=");
        sb.append(name);
        sb.append(", description=");
        sb.append(description);
        return sb.toString();
    }
}
