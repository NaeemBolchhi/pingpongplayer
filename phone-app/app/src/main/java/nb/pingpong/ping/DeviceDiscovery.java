package nb.pingpong.ping;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Finds TV apps on the local network (NSD). All callbacks arrive on the main thread. */
public class DeviceDiscovery {
    public interface Listener {
        void onDevicesChanged(List<DeviceInfo> devices);
    }

    private static final String SERVICE_TYPE = "_streamcast._tcp.";

    private final NsdManager nsd;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<DeviceInfo> devices = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean resolving = false;
    private NsdManager.DiscoveryListener discoveryListener;

    public DeviceDiscovery(Context context, Listener listener) {
        this.nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void start() {
        stop();
        devices.clear();
        queue.clear();
        resolving = false;
        listener.onDevicesChanged(new ArrayList<>(devices));

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
            }

            @Override
            public void onServiceFound(final NsdServiceInfo info) {
                String type = info.getServiceType();
                if (type != null && type.startsWith("_streamcast._tcp")) {
                    main.post(() -> {
                        queue.add(info);
                        resolveNext();
                    });
                }
            }

            @Override
            public void onServiceLost(final NsdServiceInfo info) {
                main.post(() -> {
                    for (int i = devices.size() - 1; i >= 0; i--) {
                        if (devices.get(i).serviceName.equals(info.getServiceName())) devices.remove(i);
                    }
                    listener.onDevicesChanged(new ArrayList<>(devices));
                });
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            discoveryListener = null;
        }
    }

    public void stop() {
        if (discoveryListener != null) {
            try {
                nsd.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
            discoveryListener = null;
        }
    }

    // NSD can only resolve one service at a time on many Android versions.
    private void resolveNext() {
        if (resolving || queue.isEmpty()) return;
        resolving = true;
        NsdServiceInfo info = queue.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    main.post(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }

                @Override
                public void onServiceResolved(final NsdServiceInfo si) {
                    main.post(() -> {
                        resolving = false;
                        if (si.getHost() != null) {
                            DeviceInfo d = new DeviceInfo(si.getServiceName(), si.getHost().getHostAddress(), si.getPort());
                            for (int i = devices.size() - 1; i >= 0; i--) {
                                if (devices.get(i).serviceName.equals(d.serviceName)) devices.remove(i);
                            }
                            devices.add(d);
                            listener.onDevicesChanged(new ArrayList<>(devices));
                        }
                        resolveNext();
                    });
                }
            });
        } catch (Exception e) {
            resolving = false;
            resolveNext();
        }
    }
}
