package com.example.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // Apps shown first in the dock (if installed). Everything else follows A to Z.
    private static final List<String> PREFERRED = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar",
            "com.jio.media.ctv",
            "com.spotify.tv.android",
            "com.android.vending"
    );

    private WebView web;
    private String lastApps = "";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                refresh(true);
            }
        });

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home button pressed while we are already open: jump back to the dock.
        if (web != null) web.evaluateJavascript("goHome()", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("go('back')", null);
            }
            return true; // a home screen never closes on Back
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- data

    private void refresh(final boolean force) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String apps = buildAppsJson();
                    if (force || !apps.equals(lastApps)) {
                        lastApps = apps;
                        final String inputs = buildInputsJson();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                web.evaluateJavascript("setApps(" + JSONObject.quote(apps) + ")", null);
                                web.evaluateJavascript("setInputs(" + JSONObject.quote(inputs) + ")", null);
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private String buildAppsJson() throws Exception {
        final PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();

        // TV apps first so their TV banner wins, then ordinary launcher apps.
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                if (!found.containsKey(pkg)) found.put(pkg, ri);
            }
        }

        List<ResolveInfo> list = new ArrayList<>(found.values());
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                int ia = PREFERRED.indexOf(a.activityInfo.packageName);
                int ib = PREFERRED.indexOf(b.activityInfo.packageName);
                if (ia < 0) ia = 1000;
                if (ib < 0) ib = 1000;
                if (ia != ib) return ia - ib;
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });

        JSONArray out = new JSONArray();
        for (ResolveInfo ri : list) {
            JSONObject o = new JSONObject();
            o.put("n", ri.loadLabel(pm).toString());
            o.put("p", ri.activityInfo.packageName);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (banner != null) {
                String uri = toDataUri(banner);
                if (uri != null) o.put("b", uri);
            }
            out.put(o);
        }
        return out.toString();
    }

    private String buildInputsJson() {
        JSONArray out = new JSONArray();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
            if (tim == null) return out.toString();
            Set<String> seen = new HashSet<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput()) continue;
                CharSequence label = info.loadCustomLabel(this);
                if (label == null || label.length() == 0) label = info.loadLabel(this);
                String name = label == null ? info.getId() : label.toString();
                if (!seen.add(info.getId())) continue;
                JSONObject o = new JSONObject();
                o.put("n", name);
                o.put("i", info.getId());
                out.put(o);
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    private String toDataUri(Drawable d) {
        try {
            Bitmap bm = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            c.drawColor(Color.BLACK);
            d.setBounds(0, 0, 480, 270);
            d.draw(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            bm.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------- bridge

    private void startSafely(final Intent i, final String failMsg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, failMsg, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void launch(String pkg) {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) startSafely(i, "Could not open app");
        }

        @JavascriptInterface
        public void openSettings() {
            startSafely(new Intent(Settings.ACTION_SETTINGS), "Could not open Settings");
        }

        @JavascriptInterface
        public void openWifi() {
            startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS), "Could not open Wi-Fi settings");
        }

        @JavascriptInterface
        public void openInput(String inputId) {
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
            startSafely(i, "This TV did not open that input");
        }
    }
}
