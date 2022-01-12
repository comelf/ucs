package git.comelf.common.metrics.impl;

import git.comelf.common.metrics.MetricsRecord;

import java.util.Objects;

abstract class AbstractMetricsRecord implements MetricsRecord {

    @Override public boolean equals(Object obj) {
        if (obj instanceof MetricsRecord) {
            final MetricsRecord other = (MetricsRecord) obj;
            return Objects.equals(timestamp(), other.timestamp()) &&
                    Objects.equals(name(), other.name()) &&
                    Objects.equals(description(), other.description()) &&
                    Objects.equals(tags(), other.tags())
                    ;
//                    &&
//                    Iterables.elementsEqual(metrics(), other.metrics());
        }
        return false;
    }

    // Should make sense most of the time when the record is used as a key
    @Override public int hashCode() {
        return Objects.hash(name(), description(), tags());
    }

    @Override public String toString() {
//        new StringJoiner(", ", this.getClass().getSimpleName() + "{", "}")
//                .add("timestamp=" + timestamp())
//                .add("name=" + name())
//                .add("description=" + description())
//                .add("tags=" + tags())
//                .add("metrics=" + Iterables.toString(metrics()))
//                .toString();
        return "";
    }
}
