package com.penwalker.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth Classic (SPP / RFCOMM) link to an NMEA GPS receiver such as the TOPGNSS TOP608BT.
 * The page calls these methods as window.AndroidBT.*; NMEA sentences and status come back through
 * window.__btLine(line) and window.__btStatus(state, message).
 */
@SuppressLint("MissingPermission")
public class BtBridge {
    public static final int REQ_BT = 3;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Activity act;
    private final WebView web;
    private final SharedPreferences prefs;
    private Link link;

    public BtBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
        this.prefs = act.getSharedPreferences("atpen", Context.MODE_PRIVATE);
    }

    // ---------- calls from the page ----------

    @JavascriptInterface
    public String list() {
        StringBuilder sb = new StringBuilder("{");
        boolean perm = hasPerm();
        sb.append("\"perm\":").append(perm);
        BluetoothAdapter ad = adapter();
        boolean enabled = false;
        StringBuilder devs = new StringBuilder("[");
        if (perm && ad != null) {
            try {
                enabled = ad.isEnabled();
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                boolean first = true;
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        String n = d.getName();
                        if (n == null || n.length() == 0) n = d.getAddress();
                        if (!first) devs.append(",");
                        first = false;
                        devs.append("{\"name\":").append(q(n)).append(",\"address\":").append(q(d.getAddress())).append("}");
                    }
                }
            } catch (SecurityException e) {
                perm = false;
            }
        }
        devs.append("]");
        sb.append(",\"enabled\":").append(enabled).append(",\"devices\":").append(devs).append("}");
        return sb.toString();
    }

    @JavascriptInterface
    public void requestPermission() {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    act.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
                } else {
                    js("window.__btPerm&&window.__btPerm(true)");
                }
            }
        });
    }

    @JavascriptInterface
    public void connect(final String address) {
        BluetoothAdapter ad = adapter();
        if (ad == null) { status("failed", "This device has no Bluetooth."); return; }
        if (!hasPerm()) { status("permission", "Allow Bluetooth access first."); return; }
        BluetoothDevice dev;
        try { dev = ad.getRemoteDevice(address); }
        catch (IllegalArgumentException e) { status("failed", "That Bluetooth address is not valid."); return; }
        stopLink();
        prefs.edit().putString("bt_last", address).apply();
        link = new Link(dev);
        link.start();
    }

    @JavascriptInterface
    public void disconnect() { stopLink(); }

    @JavascriptInterface
    public void openSettings() {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { act.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { }
            }
        });
    }

    // ---------- called by the activity ----------

    public void onPermissionResult(boolean granted) {
        js("window.__btPerm&&window.__btPerm(" + granted + ")");
    }

    public void shutdown() { stopLink(); }

    // ---------- internals ----------

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            || act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private synchronized void stopLink() {
        if (link != null) { link.cancel(); link = null; }
    }

    private void js(final String script) {
        web.post(new Runnable() {
            @Override public void run() { web.evaluateJavascript(script, null); }
        });
    }

    private void status(String state, String msg) {
        js("window.__btStatus&&window.__btStatus(" + q(state) + "," + q(msg) + ")");
    }

    private void emit(String line) {
        if (line.length() < 8 || line.charAt(0) != '$') return;
        String type = line.substring(3, 6);
        if (!type.equals("GGA") && !type.equals("GST")) return; // the page only needs position and error estimates
        js("window.__btLine&&window.__btLine(" + q(line) + ")");
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append("\"").toString();
    }

    private BluetoothSocket open(BluetoothDevice d) throws IOException {
        BluetoothSocket s = null;
        try {
            s = d.createRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (IOException first) {
            if (s != null) { try { s.close(); } catch (IOException ignored) { } }
            try {
                // Some GPS receivers only answer on RFCOMM channel 1.
                BluetoothSocket alt = (BluetoothSocket) d.getClass().getMethod("createRfcommSocket", int.class).invoke(d, 1);
                alt.connect();
                return alt;
            } catch (Exception e) {
                throw new IOException("Could not open the connection. Check the receiver is on and in range.");
            }
        }
    }

    private class Link extends Thread {
        private final BluetoothDevice dev;
        private volatile boolean stop = false;
        private volatile BluetoothSocket cur;

        Link(BluetoothDevice d) { dev = d; setName("bt-nmea"); }

        void cancel() {
            stop = true;
            closeQuietly(cur);
            interrupt();
        }

        private void closeQuietly(BluetoothSocket s) {
            if (s != null) { try { s.close(); } catch (IOException ignored) { } }
        }

        @Override public void run() {
            String name = dev.getName() == null ? dev.getAddress() : dev.getName();
            while (!stop) {
                BluetoothSocket sock = null;
                try {
                    status("connecting", "Connecting to " + name + "…");
                    sock = open(dev);
                    cur = sock;
                    if (stop) break;
                    status("connected", "Connected to " + name);
                    InputStream in = sock.getInputStream();
                    byte[] buf = new byte[1024];
                    StringBuilder sb = new StringBuilder();
                    while (!stop) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xff);
                            if (c == '\n') {
                                emit(sb.toString().trim());
                                sb.setLength(0);
                            } else if (c != '\r') {
                                if (sb.length() < 200) sb.append(c); else sb.setLength(0);
                            }
                        }
                    }
                } catch (SecurityException e) {
                    status("permission", "Bluetooth permission is needed.");
                    return;
                } catch (IOException e) {
                    // fall through to the retry below
                } finally {
                    closeQuietly(sock);
                    cur = null;
                }
                if (stop) break;
                status("lost", "Bluetooth link to " + name + " lost. Trying again…");
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        }
    }
}
