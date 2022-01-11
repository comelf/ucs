package git.comelf.common.metrics;

import git.comelf.common.metrics.MetricsCollector;

public interface MetricsSource {

    void getMetrics(MetricsCollector collector, boolean all);

}
