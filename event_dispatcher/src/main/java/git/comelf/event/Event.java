package git.comelf.event;

public interface Event<TYPE extends Enum<TYPE>> {
    TYPE getType();

    long getTimestamp();

    String toString();
}
