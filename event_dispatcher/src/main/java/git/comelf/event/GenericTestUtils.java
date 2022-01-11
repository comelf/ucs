package git.comelf.event;

import java.sql.Time;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class GenericTestUtils {

    public static final String ERROR_MISSING_ARGUMENT =
            "Input supplier interface should be initailized";
    public static final String ERROR_INVALID_ARGUMENT =
            "Total wait time should be greater than check interval time";

    public static void waitFor(final Supplier<Boolean> check,
                               final long checkEveryMillis, final long waitForMillis)
            throws TimeoutException, InterruptedException {
        waitFor(check, checkEveryMillis, waitForMillis, null);
    }

    public static void waitFor(final Supplier<Boolean> check,
                               final long checkEveryMillis, final long waitForMillis,
                               final String errorMsg) throws TimeoutException, InterruptedException {
        Objects.requireNonNull(check, ERROR_MISSING_ARGUMENT);
        if (waitForMillis < checkEveryMillis) {
            throw new IllegalArgumentException(ERROR_INVALID_ARGUMENT);
        }

        long st = System.currentTimeMillis();
        boolean result = check.get();

        while (!result && (System.currentTimeMillis() - st < waitForMillis)) {
            Thread.sleep(checkEveryMillis);
            result = check.get();
        }

        if (!result) {
            final String exceptionErrorMsg = "Timed out waiting for condition. "
                    + (errorMsg != null
                    ? "Error Message: " + errorMsg : "")
                    + "\nThread diagnostics:\n";
            throw new TimeoutException(exceptionErrorMsg);
        }
    }

}
