package git.comelf.common.metrics.lib;

import git.comelf.common.metrics.MetricsInfo;
import git.comelf.common.metrics.MetricsRecordBuilder;

import java.util.concurrent.atomic.AtomicInteger;

public class MutableGaugeInt extends MutableGauge {
    private AtomicInteger value = new AtomicInteger();

    MutableGaugeInt(MetricsInfo info, int initValue) {
        super(info);
        this.value.set(initValue);
    }

    public int value() {
        return value.get();
    }

    @Override
    public void incr() {
        incr(1);
    }

    /**
     * Increment by delta
     *
     * @param delta of the increment
     */
    public void incr(int delta) {
        value.addAndGet(delta);
        setChanged();
    }

    @Override
    public void decr() {
        decr(1);
    }

    /**
     * decrement by delta
     *
     * @param delta of the decrement
     */
    public void decr(int delta) {
        value.addAndGet(-delta);
        setChanged();
    }

    /**
     * Set the value of the metric
     *
     * @param value to set
     */
    public void set(int value) {
        this.value.set(value);
        setChanged();
    }

    @Override
    public void snapshot(MetricsRecordBuilder builder, boolean all) {
        if (all || changed()) {
            builder.addGauge(info(), value());
            clearChanged();
        }
    }

    /**
     * @return the value of the metric
     */
    public String toString() {
        return value.toString();
    }
}
