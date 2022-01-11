package git.comelf.common.metrics;

public interface MetricsSystemMXBean {

    /**
     * Start the metrics system
     * @throws MetricsException
     */
    public void start();

    /**
     * Stop the metrics system
     * @throws MetricsException
     */
    public void stop();

    /**
     * Start metrics MBeans
     * @throws MetricsException
     */
    public void startMetricsMBeans();

    /**
     * Stop metrics MBeans.
     * Note, it doesn't stop the metrics system control MBean,
     * i.e this interface.
     * @throws MetricsException
     */
    public void stopMetricsMBeans();

    /**
     * @return the current config
     * Avoided getConfig, as it'll turn into a "Config" attribute,
     * which doesn't support multiple line values in jconsole.
     * @throws MetricsException
     */
    public String currentConfig();
}
