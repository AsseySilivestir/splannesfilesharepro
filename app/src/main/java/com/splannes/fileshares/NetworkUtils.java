package com.splannes.fileshares;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.format.Formatter;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Network utility class for IP address detection and network state queries.
 * Uses a 3-method fallback chain: NetworkInterface → WifiManager → ConnectivityManager.
 *
 * Fixes from original:
 * - Removed duplicate/fake android.net.NetworkInterface import
 * - Prefers WiFi IP over mobile data IP
 * - No fake IP fallback (returns "0.0.0.0" instead of "192.168.43.1")
 * - Excludes 169.254.x.x link-local addresses
 * - Uses Formatter.formatIpAddress() consistently
 */
public class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    /**
     * Get the local IP address of the device.
     * Prefers WiFi/hotspot IP over mobile data IP.
     */
    public static String getLocalIpAddress(Context context) {
        if (context == null) {
            Log.e(TAG, "Context is null");
            return "0.0.0.0";
        }

        // Method 1: NetworkInterface (most reliable, no permissions needed)
        String ip = getIpFromNetworkInterfaces();
        if (!ip.equals("0.0.0.0") && isValidIpAddress(ip)) {
            Log.d(TAG, "Got IP from NetworkInterfaces: " + ip);
            return ip;
        }

        // Method 2: WifiManager
        ip = getIpFromWifiManager(context);
        if (!ip.equals("0.0.0.0") && isValidIpAddress(ip)) {
            Log.d(TAG, "Got IP from WifiManager: " + ip);
            return ip;
        }

        // Method 3: ConnectivityManager (API 28+)
        ip = getIpFromConnectivityManager(context);
        if (!ip.equals("0.0.0.0") && isValidIpAddress(ip)) {
            Log.d(TAG, "Got IP from ConnectivityManager: " + ip);
            return ip;
        }

        Log.w(TAG, "Could not get valid IP address");
        return "0.0.0.0"; // Honest failure — caller should handle this
    }

    /**
     * Get IP from network interfaces. Prefers WiFi interfaces over cellular.
     */
    private static String getIpFromNetworkInterfaces() {
        String wifiIp = "0.0.0.0";
        String hotspotIp = "0.0.0.0";
        String otherIp = "0.0.0.0";

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "0.0.0.0";

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                String name = ni.getDisplayName().toLowerCase();
                String nameLower = ni.getName().toLowerCase();

                // Skip VPN, Bluetooth, tunnel interfaces
                if (name.contains("tun") || name.contains("ppp") || name.contains("vpn") || name.contains("bt")) {
                    continue;
                }

                // Classify interface type
                boolean isWifi = name.contains("wlan") || name.contains("wifi") || nameLower.contains("wlan");
                boolean isHotspot = name.contains("ap") || nameLower.startsWith("ap") || name.contains("softap");

                // Skip cellular interfaces (IPs are behind carrier NAT, unreachable)
                boolean isCellular = name.contains("rmnet") || name.contains("ccmni") ||
                        name.contains("usbnet") || name.contains("v4-rmnet");

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;

                    String hostAddr = addr.getHostAddress();
                    if (!isUsablePrivateIp(hostAddr)) continue;

                    if (isHotspot) {
                        hotspotIp = hostAddr; // Hotspot IPs are most relevant for sharing
                    } else if (isWifi) {
                        wifiIp = hostAddr;
                    } else if (!isCellular) {
                        otherIp = hostAddr;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP from network interfaces", e);
        }

        // Priority: hotspot > WiFi > other
        if (!hotspotIp.equals("0.0.0.0")) return hotspotIp;
        if (!wifiIp.equals("0.0.0.0")) return wifiIp;
        return otherIp;
    }

    /**
     * Get IP from WifiManager.
     */
    private static String getIpFromWifiManager(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return "0.0.0.0";

            if (!wifiManager.isWifiEnabled()) return "0.0.0.0";

            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null) return "0.0.0.0";

            int ip = wifiInfo.getIpAddress();
            if (ip == 0) return "0.0.0.0";

            return Formatter.formatIpAddress(ip);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException - Missing location permission", e);
            return "0.0.0.0";
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP from WifiManager", e);
            return "0.0.0.0";
        }
    }

    /**
     * Get IP from ConnectivityManager (API 28+).
     */
    private static String getIpFromConnectivityManager(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return "0.0.0.0";

                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork == null) return "0.0.0.0";

                LinkProperties lp = cm.getLinkProperties(activeNetwork);
                if (lp == null) return "0.0.0.0";

                for (android.net.LinkAddress linkAddr : lp.getLinkAddresses()) {
                    InetAddress addr = linkAddr.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (isUsablePrivateIp(ip)) return ip;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting IP from ConnectivityManager", e);
            }
        }
        return "0.0.0.0";
    }

    /**
     * Check if IP is a private IP suitable for file sharing.
     * Excludes 169.254.x.x (link-local = misconfigured).
     */
    private static boolean isUsablePrivateIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        if (ip.startsWith("10.")) return true;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {}
        }
        if (ip.startsWith("192.168.")) return true;
        // NOT including 169.254.x.x — link-local means no real connection
        return false;
    }

    /**
     * Validate IP address format.
     */
    private static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isEmpty() || ip.equals("0.0.0.0")) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Check if the device is ready for file sharing (has a usable local IP).
     */
    public static boolean isServerReady(Context context) {
        String ip = getLocalIpAddress(context);
        return isValidIpAddress(ip) && isUsablePrivateIp(ip);
    }

    /**
     * Check if device has network connection.
     */
    public static boolean isNetworkAvailable(Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            } else {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking network", e);
            return false;
        }
    }

    /**
     * Check if connected to WiFi.
     */
    public static boolean isConnectedToWifi(Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking WiFi", e);
            return false;
        }
    }

    /**
     * Detect hotspot mode. Uses WifiManager reflection as fallback.
     * Does NOT use "has IP + no WiFi = hotspot" (that's wrong for mobile data).
     */
    public static boolean isHotspotEnabled(Context context) {
        if (context == null) return false;

        // Method 1: Check for hotspot network interface (ap0, softap)
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    String name = ni.getName().toLowerCase();
                    if ((name.startsWith("ap") || name.contains("softap")) && ni.isUp()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}

        // Method 2: Reflection on WifiManager (works on most devices)
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                java.lang.reflect.Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
                method.setAccessible(true);
                return (Boolean) method.invoke(wm);
            }
        } catch (Exception ignored) {
            // Reflection failed — likely newer Android where this is hidden
        }

        return false;
    }

    /**
     * Get connection type as a human-readable string.
     */
    public static String getConnectionType(Context context) {
        if (!isNetworkAvailable(context) && !isHotspotEnabled(context)) {
            return "No Connection";
        }
        if (isHotspotEnabled(context)) {
            return "Hotspot (" + getLocalIpAddress(context) + ")";
        }
        if (isConnectedToWifi(context)) {
            return "WiFi (" + getLocalIpAddress(context) + ")";
        }
        return "Connected (" + getLocalIpAddress(context) + ")";
    }

    /**
     * Get all local IP addresses (for debugging).
     */
    public static String getAllLocalIpAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(addr.getHostAddress()).append(" (").append(ni.getName()).append(")");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting all IPs", e);
        }
        return sb.length() > 0 ? sb.toString() : "None";
    }
}
