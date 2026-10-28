package nb.pingpong.ping;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final long SCAN_MS = 5000;

    private TextView tvStatus;
    private LinearProgressIndicator progress;
    private DeviceAdapter adapter;
    private final ArrayList<DeviceInfo> devices = new ArrayList<>();

    private DeviceDiscovery discovery;
    private UsageStore usage;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** When true, a lone TV app is opened without showing the list. Re-armed on app start / refresh. */
    private boolean autoOpenArmed = true;
    private boolean scanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_scan) {
                autoOpenArmed = true;
                startScan();
                return true;
            }
            return false;
        });

        tvStatus = findViewById(R.id.tv_status);
        progress = findViewById(R.id.progress);
        usage = new UsageStore(this);

        adapter = new DeviceAdapter();
        RecyclerView list = findViewById(R.id.device_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        discovery = new DeviceDiscovery(this, this::onDevicesChanged);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startScan();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        discovery.stop();
        scanning = false;
    }

    private void startScan() {
        scanning = true;
        progress.setVisibility(View.VISIBLE);
        discovery.start();
        updateStatus();
        handler.removeCallbacks(endScan);
        handler.postDelayed(endScan, SCAN_MS);
    }

    private final Runnable endScan = () -> {
        scanning = false;
        progress.setVisibility(View.GONE);
        updateStatus();
    };

    private void onDevicesChanged(List<DeviceInfo> found) {
        devices.clear();
        devices.addAll(found);
        usage.sort(devices);
        adapter.notifyDataSetChanged();
        updateStatus();

        handler.removeCallbacks(autoOpen);
        if (autoOpenArmed && devices.size() == 1) {
            // wait a moment in case a second TV app is about to show up
            handler.postDelayed(autoOpen, 1500);
        }
    }

    private final Runnable autoOpen = new Runnable() {
        @Override
        public void run() {
            if (autoOpenArmed && devices.size() == 1) {
                autoOpenArmed = false;
                open(devices.get(0));
            }
        }
    };

    private void updateStatus() {
        if (devices.isEmpty()) {
            tvStatus.setText(scanning ? "Looking for TV apps on this network…" : "No TV apps found");
        } else if (devices.size() == 1) {
            tvStatus.setText("1 TV app found");
        } else {
            tvStatus.setText(devices.size() + " TV apps found");
        }
    }

    private void open(DeviceInfo device) {
        autoOpenArmed = false;
        usage.record(device.serviceName);
        Intent intent = new Intent(this, ControlActivity.class);
        intent.putExtra("device", device);
        startActivity(intent);
    }

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            final TextView name, detail;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final DeviceInfo d = devices.get(position);
            int n = usage.count(d.serviceName);
            h.name.setText(d.serviceName);
            h.detail.setText(d.host + " · used " + n + (n == 1 ? " time" : " times"));
            h.itemView.setOnClickListener(v -> open(d));
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }
    }
}
