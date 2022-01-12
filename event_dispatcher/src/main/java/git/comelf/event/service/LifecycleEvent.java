package git.comelf.event.service;

public class LifecycleEvent {
    private static final long serialVersionUID = 1648576996238247836L;

    /**
     * Local time in milliseconds when the event occurred
     */
    public long time;
    /**
     * new state
     */
    public Service.STATE state;
}
