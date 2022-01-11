package git.comelf.common.metrics;

public interface MetricsPlugin {
    /**
     * Initialize the plugin
     * @param conf  the configuration object for the plugin
     */
    void init();
}
