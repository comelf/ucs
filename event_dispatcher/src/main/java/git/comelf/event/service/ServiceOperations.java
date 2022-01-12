package git.comelf.event.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ServiceOperations {
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractService.class);

    private ServiceOperations() {
    }

    /**
     * Stop a service.
     * <p>Do nothing if the service is null or not
     * in a state in which it can be/needs to be stopped.
     * <p>
     * The service state is checked <i>before</i> the operation begins.
     * This process is <i>not</i> thread safe.
     * @param service a service or null
     */
    public static void stop(Service service) {
        if (service != null) {
            service.stop();
        }
    }

    /**
     * Stop a service; if it is null do nothing. Exceptions are caught and
     * logged at warn level. (but not Throwables). This operation is intended to
     * be used in cleanup operations
     *
     * @param service a service; may be null
     * @return any exception that was caught; null if none was.
     */
    public static Exception stopQuietly(Service service) {
        return stopQuietly(LOG, service);
    }

    /**
     * Stop a service; if it is null do nothing. Exceptions are caught and
     * logged at warn level. (but not Throwables). This operation is intended to
     * be used in cleanup operations
     *
     * @param log the log to warn at
     * @param service a service; may be null
     * @return any exception that was caught; null if none was.
     * @see ServiceOperations#stopQuietly(Service)
     */
    public static Exception stopQuietly(Object log, Service service) {
        try {
            stop(service);
        } catch (Exception e) {
//            log.warn("When stopping the service " + service.getName(), e);
            return e;
        }
        return null;
    }

    /**
     * Stop a service; if it is null do nothing. Exceptions are caught and
     * logged at warn level. (but not Throwables). This operation is intended to
     * be used in cleanup operations
     *
     * @param log the log to warn at
     * @param service a service; may be null
     * @return any exception that was caught; null if none was.
     * @see ServiceOperations#stopQuietly(Service)
     */
    public static Exception stopQuietly(Logger log, Service service) {
        try {
            stop(service);
        } catch (Exception e) {
            log.warn("When stopping the service {}", service.getName(), e);
            return e;
        }
        return null;
    }

    /**
     * Class to manage a list of {@link ServiceStateChangeListener} instances,
     * including a notification loop that is robust against changes to the list
     * during the notification process.
     */
    public static class ServiceListeners {
        /**
         * List of state change listeners; it is final to guarantee
         * that it will never be null.
         */
        private final List<ServiceStateChangeListener> listeners =
                new ArrayList<ServiceStateChangeListener>();

        /**
         * Thread-safe addition of a new listener to the end of a list.
         * Attempts to re-register a listener that is already registered
         * will be ignored.
         * @param l listener
         */
        public synchronized void add(ServiceStateChangeListener l) {
            if(!listeners.contains(l)) {
                listeners.add(l);
            }
        }

        /**
         * Remove any registration of a listener from the listener list.
         * @param l listener
         * @return true if the listener was found (and then removed)
         */
        public synchronized boolean remove(ServiceStateChangeListener l) {
            return listeners.remove(l);
        }

        /**
         * Reset the listener list
         */
        public synchronized void reset() {
            listeners.clear();
        }

        /**
         * Change to a new state and notify all listeners.
         * This method will block until all notifications have been issued.
         * It caches the list of listeners before the notification begins,
         * so additions or removal of listeners will not be visible.
         * @param service the service that has changed state
         */
        public void notifyListeners(Service service) {
            //take a very fast snapshot of the callback list
            //very much like CopyOnWriteArrayList, only more minimal
            ServiceStateChangeListener[] callbacks;
            synchronized (this) {
                callbacks = listeners.toArray(new ServiceStateChangeListener[listeners.size()]);
            }
            //iterate through the listeners outside the synchronized method,
            //ensuring that listener registration/unregistration doesn't break anything
            for (ServiceStateChangeListener l : callbacks) {
                l.stateChanged(service);
            }
        }
    }
}
