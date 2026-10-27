package nb.pingpong.ping;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private TextView tvStatus;
    private ListView deviceList;
    private ArrayAdapter<String> adapter;
    private final ArrayList<String> deviceNames = new ArrayList<>();
    private final ArrayList<DeviceInfo> devices = new ArrayList<>();

    private DeviceDiscovery discovery;
    private UsageStore usage;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** When true, a lone TV app is opened without showing the list. Re-armed on app start / Scan. */
    private boolean autoOpenArmed = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnScan = findViewById(R.id.btn_scan);
        tvStatus = findViewById(R.id.tv_status);
        deviceList = findViewById(R.id.device_list);
        usage = new UsageStore(this);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
        deviceList.setAdapter(adapter);
        deviceList.setOnItemClickListener((parent, view, position, id) -> open(devices.get(position)));

        discovery = new DeviceDiscovery(this, this::onDevicesChanged);

        btnScan.setOnClickListener(v -> {
            autoOpenArmed = true;
            discovery.start();
            updateStatus();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        discovery.start();
        updateStatus();
        // if nothing shows up, say so
        handler.postDelayed(this::updateStatus, 3000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        discovery.stop();
    }

    private void onDevicesChanged(List<DeviceInfo> found) {
        devices.clear();
        devices.addAll(found);
        usage.sort(devices);
        deviceNames.clear();
        for (DeviceInfo d : devices) deviceNames.add(d.serviceName);
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
        if (devices.isEmpty()) tvStatus.setText("Looking for TV apps on this network…");
        else if (devices.size() == 1) tvStatus.setText("1 TV app found");
        else tvStatus.setText(devices.size() + " TV apps found — tap one to control it");
    }

    private void open(DeviceInfo device) {
        autoOpenArmed = false;
        usage.record(device.serviceName);
        Intent intent = new Intent(this, ControlActivity.class);
        intent.putExtra("device", device);
        startActivity(intent);
    }
}
