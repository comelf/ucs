package git.comelf.common.metrics.impl;

import git.comelf.common.metrics.AbstractMetric;
import git.comelf.common.metrics.MetricType;
import git.comelf.common.metrics.MetricsInfo;
import git.comelf.common.metrics.MetricsVisitor;

public class MetricCounterLong extends AbstractMetric {
    final long value;

    MetricCounterLong(MetricsInfo info, long value) {
        super(info);
        this.value = value;
    }

    @Override
    public Long value() {
        return value;
    }

    @Override
    public MetricType type() {
        return MetricType.COUNTER;
    }

    @Override
    public void visit(MetricsVisitor visitor) {
        visitor.counter(this, value);
    }
}
