package app.buddy.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote phone tools for computers on the user's Tailscale network.
 * Disabled by default. Each paired computer has a separate revocable bearer token.
 */
final class Tailnet {
    private Tailnet() {
    }

    static final class Device {
        final String id, name, token;
        final long added;

        Device(String id, String name, String token, long added) {
            this.id = id;
            this.name = name;
            this.token = token;
            this.added = added;
        }
    }

    static List<Device> devices(Context c) {
        List<Device> out = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(Prefs.tailnetDevices(c));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.getJSONObject(i);
                out.add(new Device(item.getString("id"), item.optString("name"),
                        item.getString("token"), item.optLong("added")));
            }
        } catch (Exception ignored) {
            // A malformed preference must not prevent the local MCP server from starting.
        }
        return out;
    }

    private static void save(Context c, List<Device> devices) {
        JSONArray saved = new JSONArray();
        try {
            for (Device device : devices) {
                saved.put(new JSONObject().put("id", device.id).put("name", device.name)
                        .put("token", device.token).put("added", device.added));
            }
        } catch (org.json.JSONException ignored) {
        }
        Prefs.setTailnetDevices(c, saved.toString());
    }

    static synchronized Device add(Context c, String name) {
        byte[] random = new byte[24];
        new java.security.SecureRandom().nextBytes(random);
        StringBuilder token = new StringBuilder("tn-");
        for (byte value : random) token.append(String.format(java.util.Locale.ROOT, "%02x", value));
        long now = System.currentTimeMillis();
        Device device = new Device(Long.toString(now, 36), name, token.toString(), now);
        List<Device> current = devices(c);
        // Re-pairing the same named computer replaces its old credential immediately.
        current.removeIf(existing -> existing.name.equals(name));
        current.add(device);
        save(c, current);
        return device;
    }

    static synchronized void remove(Context c, String id) {
        List<Device> current = devices(c);
        current.removeIf(device -> device.id.equals(id));
        save(c, current);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean pairing =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * A computer requests pairing with POST /pair?name=... from the Tailscale network.
     * The phone user must approve before a device-specific token is created and returned.
     */
    static String pair(Context c, String requestedName, InetAddress from, InetAddress phone)
            throws InterruptedException {
        if (!Prefs.tailnetTools(c) || from == null || phone == null
                || !cgnat(from.getAddress()) || !cgnat(phone.getAddress())) return null;

        String name = requestedName == null ? "" :
                requestedName.replaceAll("[^A-Za-z0-9 ._-]", "").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        if (name.isEmpty()) name = "Computer " + (devices(c).size() + 1);
        if (!pairing.compareAndSet(false, true)) return null;

        try {
            RootApproval.ensureChannel(c);
            String approval = RootApproval.request(c,
                    "Allow " + name + " (" + from.getHostAddress()
                            + ") to access this phone's tools over Tailscale?", 90_000L);
            if (!"APPROVED".equals(approval)) return null;

            java.util.function.BooleanSupplier gone = McpServer.CALLER_GONE.get();
            if (gone != null && gone.getAsBoolean()) return null;

            Device device = add(c, name);
            return new JSONObject().put("type", "http")
                    .put("url", "http://" + phone.getHostAddress() + ":" + McpServer.PORT + "/mcp")
                    .put("headers", new JSONObject().put("Authorization", "Bearer " + device.token))
                    .put("timeout", 600000).toString();
        } catch (org.json.JSONException e) {
            return null;
        } finally {
            pairing.set(false);
        }
    }

    /** Resolve a paired computer's token with a constant-time comparison. */
    static Device device(Context c, String token) {
        if (token == null || !Prefs.tailnetTools(c)) return null;
        byte[] supplied = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Device found = null;
        for (Device candidate : devices(c)) {
            if (java.security.MessageDigest.isEqual(
                    candidate.token.getBytes(java.nio.charset.StandardCharsets.UTF_8), supplied)) {
                found = candidate;
            }
        }
        return found;
    }

    /** Return only an IPv4 address on an active VPN transport, and only when the feature is enabled. */
    static InetAddress address(Context c) {
        if (!Prefs.tailnetTools(c)) return null;
        try {
            ConnectivityManager manager = c.getSystemService(ConnectivityManager.class);
            if (manager == null) return null;
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties == null) continue;
                for (LinkAddress link : properties.getLinkAddresses()) {
                    InetAddress address = link.getAddress();
                    if (address instanceof Inet4Address && cgnat(address.getAddress())) return address;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Tailscale IPv4 addresses are in 100.64.0.0/10. */
    private static boolean cgnat(byte[] bytes) {
        return bytes != null && bytes.length >= 2
                && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }
}
