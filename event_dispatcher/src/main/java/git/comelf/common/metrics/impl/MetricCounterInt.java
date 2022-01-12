package git.comelf.common.metrics.impl;

import git.comelf.common.metrics.AbstractMetric;
import git.comelf.common.metrics.MetricType;
import git.comelf.common.metrics.MetricsInfo;
import git.comelf.common.metrics.MetricsVisitor;

public class MetricCounterInt extends AbstractMetric {
    final int value;

    MetricCounterInt(MetricsInfo info, int value) {
        super(info);
        this.value = value;
    }

    @Override
    public Integer value() {
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
