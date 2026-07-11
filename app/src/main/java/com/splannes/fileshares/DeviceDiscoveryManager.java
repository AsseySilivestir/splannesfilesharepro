package com.splannes.fileshares;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages device discovery and service advertisement on the local network
 * using Android's NsdManager (mDNS/DNS-SD).
 *
 * Capabilities:
 * - Discovers other devices running the _fileshares._tcp. service
 * - Advertises this device's file sharing service on the local network
 * - Maintains a thread-safe list of discovered devices
 * - Delivers all callbacks on the main thread via Handler
 * - Compatible with API 21+ (NsdManager available since API 16)
 *
 * Usage:
 *   DeviceDiscoveryManager mgr = new DeviceDiscoveryManager(context);
 *   mgr.setListener(myListener);
 *   mgr.startDiscovery();
 *   mgr.registerService(8988, "MyPhone");
 *   // ...
 *   mgr.unregisterService();
 *   mgr.stopDiscovery();
 */
public class DeviceDiscoveryManager {

    private static final String TAG = "DeviceDiscovery";
    private static final String SERVICE_TYPE = "_fileshares._tcp.";

    private final Context context;
    private final NsdManager nsdManager;
    private final Handler mainHandler;

    // Service registration
    private NsdManager.RegistrationListener registrationListener;
    private volatile boolean isRegistered = false;

    // Discovery
    private NsdManager.DiscoveryListener discoveryListener;
    private volatile boolean isDiscovering = false;

    // Resolved services in progress (service name -> resolve listener)
    private final ConcurrentHashMap<String, NsdManager.ResolveListener> pendingResolves =
            new ConcurrentHashMap<>();

    // Discovered devices: keyed by unique service name (mDNS enforces uniqueness)
    private final ConcurrentHashMap<String, DiscoveredDevice> discoveredDevices =
            new ConcurrentHashMap<>();

    // Listener for device events
    private OnDeviceListener listener;

    // ---------------------------------------------------------------
    // Inner classes
    // ---------------------------------------------------------------

    /**
     * Represents a discovered file-sharing device on the local network.
     */
    public static class DiscoveredDevice {
        /** Friendly name advertised by the device. */
        public final String name;
        /** IP address of the device (e.g. "192.168.1.42"). */
        public final String host;
        /** Port the file server is listening on. */
        public final int port;
        /** The mDNS service name (unique on the network). Used as internal key. */
        public final String serviceName;

        public DiscoveredDevice(String name, String host, int port, String serviceName) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.serviceName = serviceName;
        }

        /**
         * Build the HTTP URL pointing at this device's file server.
         */
        public String getUrl() {
            return "http://" + host + ":" + port;
        }

        @Override
        public String toString() {
            return name + " (" + host + ":" + port + ")";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DiscoveredDevice)) return false;
            DiscoveredDevice that = (DiscoveredDevice) o;
            return serviceName != null ? serviceName.equals(that.serviceName) : that.serviceName == null;
        }

        @Override
        public int hashCode() {
            return serviceName != null ? serviceName.hashCode() : 0;
        }
    }

    /**
     * Callback interface for device discovery events.
     * All methods are invoked on the main thread.
     */
    public interface OnDeviceListener {
        /** Called when a new device is found on the network. */
        void onDeviceFound(DiscoveredDevice device);

        /** Called when a previously discovered device is no longer available. */
        void onDeviceLost(DiscoveredDevice device);

        /** Called when service registration succeeds. */
        void onServiceRegistered(String serviceName);

        /** Called when service registration fails. */
        void onServiceRegistrationFailed(String serviceName, int errorCode);

        /** Called when discovery stops (either explicitly or due to an error). */
        void onDiscoveryStopped();
    }

    // ---------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------

    public DeviceDiscoveryManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());

        NsdManager mgr = null;
        try {
            mgr = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "NsdManager not available on this device", e);
        }
        this.nsdManager = mgr;
    }

    /**
     * Set the listener for device discovery events.
     * Must be called before {@link #startDiscovery()}.
     */
    public void setListener(OnDeviceListener listener) {
        this.listener = listener;
    }

    // ---------------------------------------------------------------
    // Service Registration (Advertise this device)
    // ---------------------------------------------------------------

    /**
     * Register this device's file sharing service on the local network via mDNS.
     *
     * @param port       The port the local file server is listening on.
     * @param deviceName A human-friendly name for this device (will be the service instance name).
     */
    public void registerService(int port, String deviceName) {
        if (nsdManager == null) {
            Log.w(TAG, "NsdManager not available; cannot register service");
            return;
        }
        if (isRegistered) {
            Log.w(TAG, "Service already registered; unregister first");
            return;
        }

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        // The service instance name – mDNS will append a suffix if a collision occurs
        serviceInfo.setServiceName(deviceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                String registeredName = info.getServiceName();
                isRegistered = true;
                Log.d(TAG, "Service registered: " + registeredName);
                notifyListener(() -> {
                    if (listener != null) listener.onServiceRegistered(registeredName);
                });
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "Service registration failed: code=" + errorCode);
                notifyListener(() -> {
                    if (listener != null)
                        listener.onServiceRegistrationFailed(info.getServiceName(), errorCode);
                });
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                isRegistered = false;
                Log.d(TAG, "Service unregistered: " + info.getServiceName());
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "Service unregistration failed: code=" + errorCode);
            }
        };

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
            Log.d(TAG, "Registering service: " + deviceName + " on port " + port);
        } catch (IllegalArgumentException e) {
            // Can happen if serviceInfo has invalid parameters
            Log.e(TAG, "Invalid service registration parameters", e);
        }
    }

    /**
     * Unregister the previously registered service.
     */
    public void unregisterService() {
        if (nsdManager == null || !isRegistered || registrationListener == null) {
            return;
        }
        try {
            nsdManager.unregisterService(registrationListener);
        } catch (IllegalArgumentException e) {
            // Listener already unregistered or was never registered
            Log.w(TAG, "Unregister service called with invalid listener", e);
        }
        isRegistered = false;
        registrationListener = null;
    }

    // ---------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------

    /**
     * Start discovering file sharing services on the local network.
     */
    public void startDiscovery() {
        if (nsdManager == null) {
            Log.w(TAG, "NsdManager not available; cannot start discovery");
            return;
        }
        if (isDiscovering) {
            Log.w(TAG, "Discovery already in progress");
            return;
        }

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                isDiscovering = true;
                Log.d(TAG, "Discovery started for: " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String serviceName = serviceInfo.getServiceName();
                Log.d(TAG, "Service found: " + serviceName);
                resolveService(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String serviceName = serviceInfo.getServiceName();
                Log.d(TAG, "Service lost: " + serviceName);

                DiscoveredDevice removed = discoveredDevices.remove(serviceName);
                // Also clean up any pending resolve
                pendingResolves.remove(serviceName);

                if (removed != null) {
                    notifyListener(() -> {
                        if (listener != null) listener.onDeviceLost(removed);
                    });
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                isDiscovering = false;
                Log.d(TAG, "Discovery stopped");
                notifyListener(() -> {
                    if (listener != null) listener.onDiscoveryStopped();
                });
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: code=" + errorCode);
                isDiscovering = false;
                notifyListener(() -> {
                    if (listener != null) listener.onDiscoveryStopped();
                });
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed: code=" + errorCode);
                isDiscovering = false;
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to start discovery", e);
        }
    }

    /**
     * Stop discovering services.
     */
    public void stopDiscovery() {
        if (nsdManager == null || !isDiscovering || discoveryListener == null) {
            return;
        }
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (IllegalArgumentException e) {
            // Listener already stopped
            Log.w(TAG, "Stop discovery called with invalid listener", e);
        }
        isDiscovering = false;
        discoveryListener = null;

        // Clean up any in-flight resolves
        pendingResolves.clear();
    }

    // ---------------------------------------------------------------
    // Resolve
    // ---------------------------------------------------------------

    /**
     * Resolve a discovered service to obtain host IP and port details.
     */
    private void resolveService(NsdServiceInfo serviceInfo) {
        String serviceName = serviceInfo.getServiceName();

        // Avoid duplicate resolves for the same service
        if (discoveredDevices.containsKey(serviceName) || pendingResolves.containsKey(serviceName)) {
            return;
        }

        NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                String name = info.getServiceName();
                pendingResolves.remove(name);
                Log.e(TAG, "Resolve failed for " + name + ": code=" + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo resolvedInfo) {
                String name = resolvedInfo.getServiceName();
                pendingResolves.remove(name);

                InetAddress hostAddr = resolvedInfo.getHost();
                String host;
                if (hostAddr != null) {
                    host = hostAddr.getHostAddress();
                } else {
                    Log.w(TAG, "Resolved service has null host: " + name);
                    return;
                }

                // Only accept IPv4 addresses — IPv6 link-local addresses are often unreachable
                // for file sharing on local networks
                if (hostAddr instanceof Inet4Address) {
                    int port = resolvedInfo.getPort();
                    String deviceName = resolvedInfo.getServiceName();

                    DiscoveredDevice device = new DiscoveredDevice(deviceName, host, port, name);
                    discoveredDevices.put(name, device);
                    Log.d(TAG, "Service resolved: " + device);

                    notifyListener(() -> {
                        if (listener != null) listener.onDeviceFound(device);
                    });
                }
            }
        };

        pendingResolves.put(serviceName, resolveListener);

        try {
            nsdManager.resolveService(serviceInfo, resolveListener);
        } catch (IllegalArgumentException e) {
            pendingResolves.remove(serviceName);
            Log.e(TAG, "Failed to resolve service: " + serviceName, e);
        }
    }

    // ---------------------------------------------------------------
    // Query helpers
    // ---------------------------------------------------------------

    /**
     * Get a snapshot of currently discovered devices.
     * Thread-safe: returns a copy.
     */
    public List<DiscoveredDevice> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices.values());
    }

    /**
     * Check if we are currently discovering services.
     */
    public boolean isDiscovering() {
        return isDiscovering;
    }

    /**
     * Check if our service is currently registered.
     */
    public boolean isRegistered() {
        return isRegistered;
    }

    // ---------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------

    /**
     * Stop all discovery and unregisters any advertised service.
     * Call this in your Activity/Service onDestroy().
     */
    public void shutdown() {
        stopDiscovery();
        unregisterService();
        discoveredDevices.clear();
        pendingResolves.clear();
        listener = null;
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /**
     * Post a runnable on the main thread.  If we are already on the main
     * thread the runnable still goes through the Handler queue so ordering
     * is preserved.
     */
    private void notifyListener(Runnable r) {
        mainHandler.post(r);
    }
}
