package git.comelf.common.metrics.impl;

import git.comelf.common.metrics.MetricsCollector;

public class MetricsCollectorImpl implements MetricsCollector,
        Iterable<MetricsRecordBuilderImpl>{

    private final List<MetricsRecordBuilderImpl> rbs = Lists.newArrayList();
    private MetricsFilter recordFilter, metricFilter;

    @Override
    public MetricsRecordBuilderImpl addRecord(MetricsInfo info) {
        boolean acceptable = recordFilter == null ||
                recordFilter.accepts(info.name());
        MetricsRecordBuilderImpl rb = new MetricsRecordBuilderImpl(this, info,
                recordFilter, metricFilter, acceptable);
        if (acceptable) rbs.add(rb);
        return rb;
    }

    @Override
    public MetricsRecordBuilderImpl addRecord(String name) {
        return addRecord(info(name, name +" record"));
    }

    public List<MetricsRecordImpl> getRecords() {
        List<MetricsRecordImpl> recs = Lists.newArrayListWithCapacity(rbs.size());
        for (MetricsRecordBuilderImpl rb : rbs) {
            MetricsRecordImpl mr = rb.getRecord();
            if (mr != null) {
                recs.add(mr);
            }
        }
        return recs;
    }

    @Override
    public Iterator<MetricsRecordBuilderImpl> iterator() {
        return rbs.iterator();
    }

    public void clear() { rbs.clear(); }

    MetricsCollectorImpl setRecordFilter(MetricsFilter rf) {
        recordFilter = rf;
        return this;
    }

    MetricsCollectorImpl setMetricFilter(MetricsFilter mf) {
        metricFilter = mf;
        return this;
    }
}
