package git.comelf.common.metrics;

public interface MetricsSource {

    void getMetrics(MetricsCollector collector, boolean all);

}
