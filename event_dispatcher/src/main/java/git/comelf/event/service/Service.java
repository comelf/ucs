package git.comelf.event.service;

import java.io.IOException;

public interface Service extends Cloneable{
    /**
     * Service states
     */
    public enum STATE {
        /** Constructed but not initialized */
        NOTINITED(0, "NOTINITED"),

        /** Initialized but not started or stopped */
        INITED(1, "INITED"),

        /** started and not stopped */
        STARTED(2, "STARTED"),

        /** stopped. No further state transitions are permitted */
        STOPPED(3, "STOPPED");

        /**
         * An integer value for use in array lookup and JMX interfaces.
         * Although {@link Enum#ordinal()} could do this, explicitly
         * identify the numbers gives more stability guarantees over time.
         */
        private final int value;

        /**
         * A name of the state that can be used in messages
         */
        private final String statename;

        private STATE(int value, String name) {
            this.value = value;
            this.statename = name;
        }

        /**
         * Get the integer value of a state
         * @return the numeric value of the state
         */
        public int getValue() {
            return value;
        }

        /**
         * Get the name of a state
         * @return the state's name
         */
        @Override
        public String toString() {
            return statename;
        }
    }

    void init();
    void start();
    void stop();

    void close() throws IOException;

    String getName();
//    Configuration getConfig();
    STATE getServiceState();
    long getStartTime();
    boolean isInState(STATE state);
}
