package nb.couchpotato.phone;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private Button btnScan;
    private ListView deviceList;
    private ArrayAdapter<String> adapter;
    private ArrayList<DeviceInfo> devices = new ArrayList<>();
    private ArrayList<String> deviceNames = new ArrayList<>();
    
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private static final String SERVICE_TYPE = "_streamcast._tcp.";
    
    private SharedPreferences prefs;
    private Map<String, Integer> usageCount = new HashMap<>();
    private Map<String, Long> lastUsed = new HashMap<>();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        btnScan = findViewById(R.id.btn_scan);
        deviceList = findViewById(R.id.device_list);
        prefs = getSharedPreferences("StreamCastPrefs", Context.MODE_PRIVATE);
        
        loadUsageStats();
        
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
        deviceList.setAdapter(adapter);
        
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        
        btnScan.setOnClickListener(v -> startDiscovery());
        
        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            DeviceInfo device = devices.get(position);
            updateUsage(device.serviceName);
            Intent intent = new Intent(this, ControlActivity.class);
            intent.putExtra("device", device);
            startActivity(intent);
        });
        
        startDiscovery();
    }
    
    private void loadUsageStats() {
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getKey().startsWith("count_")) {
                String name = entry.getKey().substring(6);
                usageCount.put(name, (Integer) entry.getValue());
            } else if (entry.getKey().startsWith("time_")) {
                String name = entry.getKey().substring(5);
                lastUsed.put(name, (Long) entry.getValue());
            }
        }
    }
    
    private void updateUsage(String serviceName) {
        usageCount.put(serviceName, usageCount.getOrDefault(serviceName, 0) + 1);
        lastUsed.put(serviceName, System.currentTimeMillis());
        
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("count_" + serviceName, usageCount.get(serviceName));
        editor.putLong("time_" + serviceName, lastUsed.get(serviceName));
        editor.apply();
    }
    
    private void startDiscovery() {
        devices.clear();
        deviceNames.clear();
        adapter.notifyDataSetChanged();
        
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {}
            
            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo.getServiceType().equals(SERVICE_TYPE)) {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {}
                        
                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            DeviceInfo device = new DeviceInfo(
                                serviceInfo.getServiceName(),
                                serviceInfo.getHost(),
                                serviceInfo.getPort()
                            );
                            runOnUiThread(() -> {
                                devices.add(device);
                                sortDevices();
                                deviceNames.clear();
                                for (DeviceInfo d : devices) {
                                    deviceNames.add(d.serviceName);
                                }
                                adapter.notifyDataSetChanged();
                            });
                        }
                    });
                }
            }
            
            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                runOnUiThread(() -> {
                    devices.removeIf(d -> d.serviceName.equals(serviceInfo.getServiceName()));
                    deviceNames.clear();
                    for (DeviceInfo d : devices) {
                        deviceNames.add(d.serviceName);
                    }
                    adapter.notifyDataSetChanged();
                });
            }
            
            @Override
            public void onDiscoveryStopped(String serviceType) {}
            
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Toast.makeText(MainActivity.this, "Discovery failed", Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };
        
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }
    
    private void sortDevices() {
        Collections.sort(devices, new Comparator<DeviceInfo>() {
            @Override
            public int compare(DeviceInfo d1, DeviceInfo d2) {
                int count1 = usageCount.getOrDefault(d1.serviceName, 0);
                int count2 = usageCount.getOrDefault(d2.serviceName, 0);
                if (count1 != count2) {
                    return Integer.compare(count2, count1); // higher frequency first
                }
                long time1 = lastUsed.getOrDefault(d1.serviceName, 0L);
                long time2 = lastUsed.getOrDefault(d2.serviceName, 0L);
                return Long.compare(time2, time1); // more recent first
            }
        });
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nsdManager != null && discoveryListener != null) {
            nsdManager.stopServiceDiscovery(discoveryListener);
        }
    }
    
    public static class DeviceInfo implements android.os.Parcelable {
        String serviceName;
        java.net.InetAddress host;
        int port;
        
        public DeviceInfo(String serviceName, java.net.InetAddress host, int port) {
            this.serviceName = serviceName;
            this.host = host;
            this.port = port;
        }
        
        protected DeviceInfo(android.os.Parcel in) {
            serviceName = in.readString();
            host = (java.net.InetAddress) in.readSerializable();
            port = in.readInt();
        }
        
        public static final Creator<DeviceInfo> CREATOR = new Creator<DeviceInfo>() {
            @Override
            public DeviceInfo createFromParcel(android.os.Parcel in) {
                return new DeviceInfo(in);
            }
            @Override
            public DeviceInfo[] newArray(int size) {
                return new DeviceInfo[size];
            }
        };
        
        @Override
        public int describeContents() {
            return 0;
        }
        
        @Override
        public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeString(serviceName);
            dest.writeSerializable(host);
            dest.writeInt(port);
        }
    }
}
