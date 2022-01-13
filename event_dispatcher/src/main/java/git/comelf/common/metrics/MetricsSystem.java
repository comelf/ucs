package git.comelf.common.metrics;

public abstract class MetricsSystem implements MetricsSystemMXBean {
    public abstract MetricsSystem init(String prefix);

    /**
     * Register a metrics source
     *
     * @param <T>    the actual type of the source object
     * @param source object to register
     * @param name   of the source. Must be unique or null (then extracted from
     *               the annotations of the source object.)
     * @param desc   the description of the source (or null. See above.)
     * @return the source object
     * @throws MetricsException
     */
    public abstract <T> T register(String name, String desc, T source);

    /**
     * Unregister a metrics source
     *
     * @param name of the source. This is the name you use to call register()
     */
    public abstract void unregisterSource(String name);

    /**
     * Register a metrics source (deriving name and description from the object)
     *
     * @param <T>    the actual type of the source object
     * @param source object to register
     * @return the source object
     * @throws MetricsException
     */
    public <T> T register(T source) {
        return register(null, null, source);
    }

    /**
     * @param name of the metrics source
     * @return the metrics source (potentially wrapped) object
     */
    public abstract MetricsSource getSource(String name);

    /**
     * Register a metrics sink
     *
     * @param <T>  the type of the sink
     * @param sink to register
     * @param name of the sink. Must be unique.
     * @param desc the description of the sink
     * @return the sink
     * @throws MetricsException
     */
    public abstract <T extends MetricsSink> T register(String name, String desc, T sink);

    /**
     * Register a callback interface for JMX events
     *
     * @param callback the callback object implementing the MBean interface.
     */
    public abstract void register(Callback callback);

    /**
     * Requests an immediate publish of all metrics from sources to sinks.
     * <p>
     * This is a "soft" request: the expectation is that a best effort will be
     * done to synchronously snapshot the metrics from all the sources and put
     * them in all the sinks (including flushing the sinks) before returning to
     * the caller. If this can't be accomplished in reasonable time it's OK to
     * return to the caller before everything is done.
     */
    public abstract void publishMetricsNow();

    /**
     * Shutdown the metrics system completely (usually during server shutdown.)
     * The MetricsSystemMXBean will be unregistered.
     *
     * @return true if shutdown completed
     */
    public abstract boolean shutdown();

    /**
     * The metrics system callback interface (needed for proxies.)
     */
    public interface Callback {
        /**
         * Called before start()
         */
        void preStart();

        /**
         * Called after start()
         */
        void postStart();

        /**
         * Called before stop()
         */
        void preStop();

        /**
         * Called after stop()
         */
        void postStop();
    }

    /**
     * Convenient abstract class for implementing callback interface
     */
    public static abstract class AbstractCallback implements Callback {
        @Override
        public void preStart() {
        }

        @Override
        public void postStart() {
        }

        @Override
        public void preStop() {
        }

        @Override
        public void postStop() {
        }
    }

}
