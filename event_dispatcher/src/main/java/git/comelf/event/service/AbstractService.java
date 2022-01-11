package git.comelf.event.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractService implements Service {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractService.class);

    private Service.STATE state = Service.STATE.NOTINITED;
    private final String name;
    private long startTime;
    private final ServiceStateModel stateModel;

    //    private List<ServiceStateChangeListener> listeners =
//            new ArrayList<ServiceStateChangeListener>();

    /**
     * History of lifecycle transitions
     */
//    private final List<LifecycleEvent> lifecycleHistory
//            = new ArrayList<LifecycleEvent>(5);

    private final Map<String, String> blockerMap = new HashMap<String, String>();

    private final Object stateChangeLock = new Object();

    /**
     * The cause of any failure -will be null.
     * if a service did not stop due to a failure.
     */
    private Exception failureCause;

    /**
     * the state in which the service was when it failed.
     * Only valid when the service is stopped due to a failure
     */
    private STATE failureState = null;

    /**
     * object used to co-ordinate {@link #waitForServiceToStop(long)}
     * across threads.
     */
    private final AtomicBoolean terminationNotification =
            new AtomicBoolean(false);

    public AbstractService(String name) {
        this.name = name;
        stateModel = new ServiceStateModel(name);
    }

    @Override
    public synchronized Service.STATE getServiceState() {
        return stateModel.getState();
    }

//    @Override
//    public final synchronized Throwable getFailureCause() {
//        return failureCause;
//    }
//
//    @Override
//    public synchronized STATE getFailureState() {
//        return failureState;
//    }


    public synchronized void init() {
        if (isInState(STATE.INITED)) {
            return;
        }
        synchronized (stateChangeLock) {
            if (enterState(STATE.INITED) != STATE.INITED) {
                try {
                    serviceInit();
                    if (isInState(STATE.INITED)) {
                        //if the service ended up here during init,
                        //notify the listeners
//                        notifyListeners();
                    }
                } catch (Exception e) {
                    noteFailure(e);
//                    ServiceOperations.stopQuietly(LOG, this);
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public synchronized void start() {
        if (isInState(STATE.STARTED)) {
            return;
        }
        //enter the started state
        synchronized (stateChangeLock) {
            if (stateModel.enterState(STATE.STARTED) != STATE.STARTED) {
                try {
                    startTime = System.currentTimeMillis();
                    serviceStart();
                    if (isInState(STATE.STARTED)) {
                        //if the service started (and isn't now in a later state), notify
                        LOG.debug("Service {} is started", getName());
//                        notifyListeners();
                    }
                } catch (Exception e) {
                    noteFailure(e);
//                    ServiceOperations.stopQuietly(LOG, this);
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (isInState(STATE.STOPPED)) {
            return;
        }
        synchronized (stateChangeLock) {
            if (enterState(STATE.STOPPED) != STATE.STOPPED) {
                try {
                    serviceStop();
                } catch (Exception e) {
                    //stop-time exceptions are logged if they are the first one,
                    noteFailure(e);
                    throw new RuntimeException(e);
                } finally {
                    //report that the service has terminated
                    terminationNotification.set(true);
                    synchronized (terminationNotification) {
                        terminationNotification.notifyAll();
                    }
                    //notify anything listening for events
//                    notifyListeners();
                }
            } else {
                //already stopped: note it
                LOG.debug("Ignoring re-entrant call to stop()");
            }
        }
    }

//    @Override
//    public synchronized void register(ServiceStateChangeListener l) {
//        listeners.add(l);
//    }
//
//    @Override
//    public synchronized void unregister(ServiceStateChangeListener l) {
//        listeners.remove(l);
//    }

    @Override
    public String getName() {
        return name;
    }

//    @Override
//    public synchronized Configuration getConfig() {
//        return config;
//    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    private void ensureCurrentState(STATE currentState) {
        if (state != currentState) {
            throw new IllegalStateException("For this operation, current State must " +
                    "be " + currentState + " instead of " + state);
        }
    }

    private void changeState(STATE newState) {
        state = newState;
        //notify listeners
//        for (ServiceStateChangeListener l : listeners) {
//            l.stateChanged(this);
//        }
    }

    @Override
    public final void close() throws IOException {
        stop();
    }

    @Override
    public boolean isInState(STATE expected) {
        return stateModel.isInState(expected);
    }

    protected final void noteFailure(Exception exception) {
        LOG.debug("noteFailure", exception);
        if (exception == null) {
            //make sure failure logic doesn't itself cause problems
            return;
        }
        //record the failure details, and log it
        synchronized (this) {
            if (failureCause == null) {
                failureCause = exception;
                failureState = getServiceState();
                LOG.info("Service {} failed in state {}",
                        getName(), failureState, exception);
            }
        }
    }

    public final boolean waitForServiceToStop(long timeout) {
        boolean completed = terminationNotification.get();
        while (!completed) {
            try {
                synchronized (terminationNotification) {
                    terminationNotification.wait(timeout);
                }
                // here there has been a timeout, the object has terminated,
                // or there has been a spurious wakeup (which we ignore)
                completed = true;
            } catch (InterruptedException e) {
                // interrupted; have another look at the flag
                completed = terminationNotification.get();
            }
        }
        return terminationNotification.get();
    }

    protected void serviceInit() throws Exception {
    }

    protected void serviceStart() throws Exception {
    }

    protected void serviceStop() throws Exception {
    }

    private STATE enterState(STATE newState) {
        assert stateModel != null : "null state in " + name + " " + this.getClass();
        STATE oldState = stateModel.enterState(newState);
        if (oldState != newState) {
            LOG.debug("Service: {} entered state {}", getName(), getServiceState());

//            recordLifecycleEvent();
        }
        return oldState;
    }

    @Override
    public String toString() {
        return "Service " + name + " in state " + stateModel;
    }

    /**
     * Put a blocker to the blocker map -replacing any
     * with the same name.
     * @param name blocker name
     * @param details any specifics on the block. This must be non-null.
     */
    protected void putBlocker(String name, String details) {
        synchronized (blockerMap) {
            blockerMap.put(name, details);
        }
    }

    /**
     * Remove a blocker from the blocker map -
     * this is a no-op if the blocker is not present
     * @param name the name of the blocker
     */
    public void removeBlocker(String name) {
        synchronized (blockerMap) {
            blockerMap.remove(name);
        }
    }

    public Map<String, String> getBlockers() {
        synchronized (blockerMap) {
            return Collections.unmodifiableMap(new HashMap<>(blockerMap));
        }
    }

}
