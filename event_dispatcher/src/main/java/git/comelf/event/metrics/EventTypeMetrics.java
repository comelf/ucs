package git.comelf.event.metrics;

public interface EventTypeMetrics <T extends Enum<T>> {

    void increment(T type, long processingTimeUs);

    long get(T type);

}
