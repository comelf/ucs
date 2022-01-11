package git.comelf.common.metrics.lib;

import git.comelf.common.metrics.MetricsRecordBuilder;

public abstract class MutableMetric {

    private volatile boolean changed = true;

    /**
     * Get a snapshot of the metric
     * @param builder the metrics record builder
     * @param all if true, snapshot unchanged metrics as well
     */
    public abstract void snapshot(MetricsRecordBuilder builder, boolean all);

    /**
     * Get a snapshot of metric if changed
     * @param builder the metrics record builder
     */
    public void snapshot(MetricsRecordBuilder builder) {
        snapshot(builder, false);
    }

    /**
     * Set the changed flag in mutable operations
     */
    protected void setChanged() { changed = true; }

    /**
     * Clear the changed flag in the snapshot operations
     */
    protected void clearChanged() { changed = false; }

    /**
     * @return  true if metric is changed since last snapshot
     */
    public boolean changed() { return changed; }
}
