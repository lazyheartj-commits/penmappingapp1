package com.penwalker.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_LOC = 1, REQ_FILE = 2;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private WebView web;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;
    private ValueCallback<Uri[]> chooser;
    private BtBridge bt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    geoCb = cb; geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
                }
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); }
                catch (Exception e) { chooser = null; return false; }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        bt = new BtBridge(this, web);
        web.addJavascriptInterface(bt, "AndroidBT");
        web.loadUrl(HOME);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == BtBridge.REQ_BT) {
            bt.onPermissionResult(res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED);
            return;
        }
        if (code == REQ_LOC && geoCb != null) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            geoCb.invoke(geoOrigin, ok, false);
            geoCb = null;
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_FILE && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            chooser = null;
        }
    }

    @Override public void onBackPressed() { moveTaskToBack(true); }

    @Override protected void onDestroy() {
        if (bt != null) bt.shutdown();
        super.onDestroy();
    }

    /** Called from the page: AndroidBridge.saveFile(name, text, mime). Saves into Downloads. */
    private class Bridge {
        @JavascriptInterface public String saveFile(String name, String text, String mime) {
            try {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "text/plain" : mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (u == null) return "Could not create file";
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                    return "Saved " + name + " to Downloads";
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                    File f = new File(dir, name);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                    return "Saved to " + f.getAbsolutePath();
                }
            } catch (Exception e) { return "Save failed: " + e.getMessage(); }
        }
    }
}
