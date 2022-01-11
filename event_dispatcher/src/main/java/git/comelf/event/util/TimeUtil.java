package git.comelf.event.util;

public class TimeUtil {
    public static long getTime() {
        return System.currentTimeMillis();
    }

    public static long diffFromNow(long time) {
        return getTime() - time;
    }
}
