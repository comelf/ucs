package git.comelf.conf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Properties;

public class Configuration {

    private static final Logger LOG = LoggerFactory.getLogger(Configuration.class);

    private Properties properties;

    public Configuration() {
    }

    protected synchronized Properties getProps() {
        if (properties == null) {
            properties = new Properties();
        }
        return properties;
    }

    public String get(String name) {
        return getProps().getProperty(name);
    }

    public void setInt(String name, int value) {
        set(name, Integer.toString(value));
    }

    public void set(String name, String value) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(value);
        name = name.trim();
        getProps().setProperty(name, value);
    }

    public int getInt(String name, int defaultValue) {
        String valueString = getTrimmed(name);
        if (valueString == null)
            return defaultValue;
        try {
            return Integer.parseInt(valueString);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    public String getTrimmed(String name) {
        String value = get(name);
        if (null == value) {
            return null;
        } else {
            return value.trim();
        }
    }

}
