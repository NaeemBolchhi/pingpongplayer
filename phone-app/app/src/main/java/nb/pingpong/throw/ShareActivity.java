package nb.couchpotato.phone;

import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;

public class ShareActivity extends AppCompatActivity {
    private String sharedUrl;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private static final String SERVICE_TYPE = "_streamcast._tcp.";
    private ArrayList<MainActivity.DeviceInfo> devices = new ArrayList<>();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();
        
        if (Intent.ACTION_SEND.equals(action) && type != null && "text/plain".equals(type)) {
            sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedUrl == null) {
                sharedUrl = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            }
            
            nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
            discoverAndShare();
        } else {
            finish();
        }
    }
    
    private void discoverAndShare() {
        devices.clear();
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
                            MainActivity.DeviceInfo device = new MainActivity.DeviceInfo(
                                serviceInfo.getServiceName(),
                                serviceInfo.getHost(),
                                serviceInfo.getPort()
                            );
                            runOnUiThread(() -> {
                                devices.add(device);
                                if (devices.size() == 1) {
                                    // Only one TV app available, forward directly
                                    sendToDevice(device);
                                } else if (devices.size() > 1) {
                                    // Multiple available, show selection
                                    showDeviceSelection();
                                }
                            });
                        }
                    });
                }
            }
            
            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {}
            @Override
            public void onDiscoveryStopped(String serviceType) {}
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };
        
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        
        // Stop discovery after 2 seconds
        new android.os.Handler().postDelayed(() -> {
            if (nsdManager != null && discoveryListener != null) {
                nsdManager.stopServiceDiscovery(discoveryListener);
            }
            if (devices.isEmpty()) {
                Toast.makeText(this, "No TV apps found", Toast.LENGTH_SHORT).show();
                finish();
            }
        }, 2000);
    }
    
    private void showDeviceSelection() {
        if (devices.isEmpty()) {
            finish();
            return;
        }
        
        String[] deviceNames = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            deviceNames[i] = devices.get(i).serviceName;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select TV to cast to");
        builder.setItems(deviceNames, (dialog, which) -> {
            sendToDevice(devices.get(which));
        });
        builder.setOnCancelListener(dialog -> finish());
        builder.show();
    }
    
    private void sendToDevice(MainActivity.DeviceInfo device) {
        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                String message = "PLAY:" + sharedUrl;
                byte[] data = message.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, device.host, device.port);
                socket.send(packet);
                socket.close();
                runOnUiThread(() -> {
                    Toast.makeText(ShareActivity.this, "Sent to " + device.serviceName, Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(ShareActivity.this, "Failed to send", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {}
        }
    }
}
