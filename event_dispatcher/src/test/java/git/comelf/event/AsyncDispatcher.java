package git.comelf.event;

import git.comelf.event.metrics.EventTypeMetrics;
import git.comelf.event.service.AbstractService;
import git.comelf.event.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MarkerFactory;

import java.util.*;
import java.util.concurrent.*;

public class AsyncDispatcher extends AbstractService implements Dispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(AsyncDispatcher.class);

    private final BlockingQueue<Event> eventQueue;
    private volatile int lastEventQueueSizeLogged = 0;
    private volatile int lastEventDetailsQueueSizeLogged = 0;
    private volatile boolean stopped = false;

    //Configuration for control the details queue event printing.
    private int detailsInterval;
    private boolean printTrigger = false;

    // Configuration flag for enabling/disabling draining dispatcher's events on
    // stop functionality.
    private volatile boolean drainEventsOnStop = false;

    // Indicates all the remaining dispatcher's events on stop have been drained
    // and processed.
    // Race condition happens if dispatcher thread sets drained to true between
    // handler setting drained to false and enqueueing event. YARN-3878 decided
    // to ignore it because of its tiny impact. Also see YARN-5436.
    private volatile boolean drained = true;
    private final Object waitForDrained = new Object();

    // For drainEventsOnStop enabled only, block newly coming events into the
    // queue while stopping.
    private volatile boolean blockNewEvents = false;
    private final EventHandler<Event> handlerInstance = new GenericEventHandler();

    private Thread eventHandlingThread;
    protected final Map<Class<? extends Enum>, EventHandler> eventDispatchers;
    private boolean exitOnDispatchException = true;

    private Map<Class<? extends Enum>,
            EventTypeMetrics> eventTypeMetricsMap;

    private ThreadPoolExecutor printEventDetailsExecutor;

    /**
     * The thread name for dispatcher.
     */
    private String dispatcherThreadName = "AsyncDispatcher event handler";

    public AsyncDispatcher() {
        this(new LinkedBlockingQueue<Event>());
    }

    public AsyncDispatcher(BlockingQueue<Event> eventQueue) {
        super("Dispatcher");
        this.eventQueue = eventQueue;
        this.eventDispatchers = new HashMap<Class<? extends Enum>, EventHandler>();
        this.eventTypeMetricsMap = new HashMap<Class<? extends Enum>, EventTypeMetrics>();
    }

    /**
     * Set a name for this dispatcher thread.
     *
     * @param dispatcherName name of the dispatcher thread
     */
    public AsyncDispatcher(String dispatcherName) {
        this();
        dispatcherThreadName = dispatcherName;
    }

    Runnable createThread() {
        return new Runnable() {
            @Override
            public void run() {
                while (!stopped && !Thread.currentThread().isInterrupted()) {
                    drained = eventQueue.isEmpty();
                    // blockNewEvents is only set when dispatcher is draining to stop,
                    // adding this check is to avoid the overhead of acquiring the lock
                    // and calling notify every time in the normal run of the loop.
                    if (blockNewEvents) {
                        synchronized (waitForDrained) {
                            if (drained) {
                                waitForDrained.notify();
                            }
                        }
                    }
                    Event event;
                    try {
                        event = eventQueue.take();
                    } catch (InterruptedException ie) {
                        if (!stopped) {
                            LOG.warn("AsyncDispatcher thread interrupted", ie);
                        }
                        return;
                    }
                    if (event != null) {
                        if (eventTypeMetricsMap.
                                get(event.getType().getDeclaringClass()) != null) {
                            long startTime = TimeUtil.getTime();
                            dispatch(event);
                            eventTypeMetricsMap.get(event.getType().getDeclaringClass())
                                    .increment(event.getType(), TimeUtil.diffFromNow(startTime));
                        } else {
                            dispatch(event);
                        }
                        if (printTrigger) {
                            //Log the latest dispatch event type
                            // may cause the too many events queued
                            LOG.info("Latest dispatch event type: " + event.getType());
                            printTrigger = false;
                        }
                    }
                }
            }
        };
    }

    public void disableExitOnDispatchException() {
        exitOnDispatchException = false;
    }

    protected void serviceInit() throws Exception {
        super.init();
//        this.detailsInterval = getConfig().getInt(YarnConfiguration.
//                        YARN_DISPATCHER_PRINT_EVENTS_INFO_THRESHOLD,
//                YarnConfiguration.
//                        DEFAULT_YARN_DISPATCHER_PRINT_EVENTS_INFO_THRESHOLD);

//        ThreadFactory threadFactory = new ThreadFactoryBuilder()
//                .setNameFormat("PrintEventDetailsThread #%d")
//                .build();
        // Thread pool for async print event details,
        // to prevent wasting too much time for RM.
        printEventDetailsExecutor = new ThreadPoolExecutor(
                1, 5, 10, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()
//                , threadFactory
        );
    }

    protected void serviceStart() throws Exception {
        //start all the components
        eventHandlingThread = new Thread(createThread());
        eventHandlingThread.setName(dispatcherThreadName);
        eventHandlingThread.start();
    }

    public void setDrainEventsOnStop() {
        drainEventsOnStop = true;
    }

    protected void serviceStop() throws Exception {
        if (drainEventsOnStop) {
            blockNewEvents = true;
            LOG.info("AsyncDispatcher is draining to stop, ignoring any new events.");
            long endTime = AsyncEventDispatcher.DEFAULT_DISPATCHER_DRAIN_EVENTS_TIMEOUT;

            synchronized (waitForDrained) {
                while (!isDrained() && eventHandlingThread != null
                        && eventHandlingThread.isAlive()
                        && System.currentTimeMillis() < endTime) {
                    waitForDrained.wait(100);
                    LOG.info("Waiting for AsyncDispatcher to drain. Thread state is :" +
                            eventHandlingThread.getState());
                }
            }
        }
        stopped = true;
        if (eventHandlingThread != null) {
            eventHandlingThread.interrupt();
            try {
                eventHandlingThread.join();
            } catch (InterruptedException ie) {
                LOG.warn("Interrupted Exception while stopping", ie);
            }
        }
        printEventDetailsExecutor.shutdownNow();

        // stop all the components
//        super.serviceStop();
    }

    @SuppressWarnings("unchecked")
    protected void dispatch(Event event) {
        //all events go thru this loop
        LOG.debug("Dispatching the event {}.{}", event.getClass().getName(),
                event);

        Class<? extends Enum> type = event.getType().getDeclaringClass();

        try {
            EventHandler handler = eventDispatchers.get(type);
            if (handler != null) {
                handler.handle(event);
            } else {
                throw new Exception("No handler for registered for " + type);
            }
        } catch (Throwable t) {
            //TODO Maybe log the state of the queue
            LOG.error(MarkerFactory.getMarker("FATAL"), "Error in dispatcher thread", t);
            // If serviceStop is called, we should exit this thread gracefully.
            if (exitOnDispatchException
//                    && (ShutdownHookManager.get().isShutdownInProgress()) == false
                    && stopped == false) {
                stopped = true;
                Thread shutDownThread = new Thread(createShutDownThread());
                shutDownThread.setName("AsyncDispatcher ShutDown handler");
                shutDownThread.start();
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void register(Class<? extends Enum> eventType,
                         EventHandler handler) {
        /* check to see if we have a listener registered */
        EventHandler<Event> registeredHandler = (EventHandler<Event>)
                eventDispatchers.get(eventType);
        LOG.info("Registering " + eventType + " for " + handler.getClass());
        if (registeredHandler == null) {
            eventDispatchers.put(eventType, handler);
        } else if (!(registeredHandler instanceof MultiListenerHandler)) {
            /* for multiple listeners of an event add the multiple listener handler */
            MultiListenerHandler multiHandler = new MultiListenerHandler();
            multiHandler.addHandler(registeredHandler);
            multiHandler.addHandler(handler);
            eventDispatchers.put(eventType, multiHandler);
        } else {
            /* already a multilistener, just add to it */
            MultiListenerHandler multiHandler
                    = (MultiListenerHandler) registeredHandler;
            multiHandler.addHandler(handler);
        }
    }

    @Override
    public EventHandler<Event> getEventHandler() {
        return handlerInstance;
    }

    class GenericEventHandler implements EventHandler<Event> {
        private void printEventQueueDetails() {
            Iterator<Event> iterator = eventQueue.iterator();
            Map<Enum, Long> counterMap = new HashMap<>();
            while (iterator.hasNext()) {
                Enum eventType = iterator.next().getType();
                if (!counterMap.containsKey(eventType)) {
                    counterMap.put(eventType, 0L);
                }
                counterMap.put(eventType, counterMap.get(eventType) + 1);
            }
            for (Map.Entry<Enum, Long> entry : counterMap.entrySet()) {
                long num = entry.getValue();
                LOG.info("Event type: " + entry.getKey()
                        + ", Event record counter: " + num);
            }
        }

        public void handle(Event event) {
            if (blockNewEvents) {
                return;
            }
            drained = false;

            /* all this method does is enqueue all the events onto the queue */
            int qSize = eventQueue.size();
            if (qSize != 0 && qSize % 1000 == 0
                    && lastEventQueueSizeLogged != qSize) {
                lastEventQueueSizeLogged = qSize;
                LOG.info("Size of event-queue is " + qSize);
            }
            if (qSize != 0 && qSize % detailsInterval == 0
                    && lastEventDetailsQueueSizeLogged != qSize) {
                lastEventDetailsQueueSizeLogged = qSize;
                printEventDetailsExecutor.submit(this::printEventQueueDetails);
                printTrigger = true;
            }
            int remCapacity = eventQueue.remainingCapacity();
            if (remCapacity < 1000) {
                LOG.warn("Very low remaining capacity in the event-queue: "
                        + remCapacity);
            }
            try {
                eventQueue.put(event);
            } catch (InterruptedException e) {
                if (!stopped) {
                    LOG.warn("AsyncDispatcher thread interrupted", e);
                }
                // Need to reset drained flag to true if event queue is empty,
                // otherwise dispatcher will hang on stop.
                drained = eventQueue.isEmpty();
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Multiplexing an event. Sending it to different handlers that
     * are interested in the event.
     *
     * @param <T> the type of event these multiple handlers are interested in.
     */
    static class MultiListenerHandler implements EventHandler<Event> {
        List<EventHandler<Event>> listofHandlers;

        public MultiListenerHandler() {
            listofHandlers = new ArrayList<EventHandler<Event>>();
        }

        @Override
        public void handle(Event event) {
            for (EventHandler<Event> handler : listofHandlers) {
                handler.handle(event);
            }
        }

        void addHandler(EventHandler<Event> handler) {
            listofHandlers.add(handler);
        }

    }

    Runnable createShutDownThread() {
        return new Runnable() {
            @Override
            public void run() {
                LOG.info("Exiting, bbye..");
                System.exit(-1);
            }
        };
    }

    protected boolean isEventThreadWaiting() {
        return eventHandlingThread.getState() == Thread.State.WAITING;
    }

    protected boolean isDrained() {
        return drained;
    }

    protected boolean isStopped() {
        return stopped;
    }

    public void addMetrics(EventTypeMetrics metrics,
                           Class<? extends Enum> eventClass) {
        eventTypeMetricsMap.put(eventClass, metrics);
    }

    public int getEventQueueSize() {
        return eventQueue.size();
    }
}
