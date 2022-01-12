package git.comelf.common.metrics.lib;

import java.util.HashMap;
import java.util.Map;

public class UniqueNames {

    static class Count {
        final String baseName;
        int value;

        Count(String name, int value) {
            baseName = name;
            this.value = value;
        }
    }

    //    static final Joiner joiner = Joiner.on('-');
    final Map<String, Count> map = new HashMap<>();

    public synchronized String uniqueName(String name) {
        Count c = map.get(name);
        if (c == null) {
            c = new Count(name, 0);
            map.put(name, c);
            return name;
        }
        if (!c.baseName.equals(name)) c = new Count(name, 0);
        do {
            String newName = "" + name + ++c.value;
            Count c2 = map.get(newName);
            if (c2 == null) {
                map.put(newName, c);
                return newName;
            }
            // handle collisons, assume to be rare cases,
            // eg: people explicitly passed in name-\d+ names.
        } while (true);
    }
}
