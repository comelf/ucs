package git.comelf.event;

public interface Dispatcher {

    EventHandler<Event> getEventHandler();

    void register(Class<? extends Enum> eventType, EventHandler handler);

}
