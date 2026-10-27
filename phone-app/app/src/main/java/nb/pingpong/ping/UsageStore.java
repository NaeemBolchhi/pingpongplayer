package nb.pingpong.ping;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Remembers how often / how recently each TV app was used. */
public class UsageStore {
    private final SharedPreferences prefs;

    public UsageStore(Context context) {
        prefs = context.getSharedPreferences("StreamCastPrefs", Context.MODE_PRIVATE);
    }

    public void record(String name) {
        prefs.edit()
                .putInt("count_" + name, prefs.getInt("count_" + name, 0) + 1)
                .putLong("time_" + name, System.currentTimeMillis())
                .apply();
    }

    /** Most used first; ties broken by most recently used. */
    public void sort(List<DeviceInfo> devices) {
        Collections.sort(devices, new Comparator<DeviceInfo>() {
            @Override
            public int compare(DeviceInfo a, DeviceInfo b) {
                int ca = prefs.getInt("count_" + a.serviceName, 0);
                int cb = prefs.getInt("count_" + b.serviceName, 0);
                if (ca != cb) return Integer.compare(cb, ca);
                long ta = prefs.getLong("time_" + a.serviceName, 0L);
                long tb = prefs.getLong("time_" + b.serviceName, 0L);
                return Long.compare(tb, ta);
            }
        });
    }
}
