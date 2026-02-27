package ovh.lyano.market.vending;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Window;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import android.support.v4.app.NotificationCompat;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView webView;
    private volatile boolean isDownloading = false;

    private static final int DOWNLOAD_NOTIFICATION_ID = 1;
    private NotificationManager notificationManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        webView = (WebView) findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        // disable cache
        webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        webView.getSettings().setAppCacheEnabled(false);
        webView.clearCache(true);
        webView.clearHistory();

        // disable the scrollbar and zoom
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.getSettings().setBuiltInZoomControls(false);
        webView.getSettings().setSupportZoom(false);

        // ✅ JavaScript to Android Bridge thingy idk
        webView.addJavascriptInterface(new WebAppBridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                showOfflinePage();
            }

            private void showOfflinePage() {
                webView.loadUrl("file:///android_asset/offline.html");
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype,
                                        long contentLength) {
                if (url.endsWith(".apk")) {
                    downloadAndInstallApk(url);
                } else {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                }
            }
        });
    //website page to load below. (eg: Market Reborn / Reborn Engine JSbridge compatibile webapp)
        webView.loadUrl("http://market.lyano.ovh");
    }

    // The actual JS Bridge (idk if this is the proper way to do this)
    public class WebAppBridge {
        Context mContext;
        WebAppBridge(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public boolean isAppInstalled(String packageName) {
            try {
                mContext.getPackageManager().getPackageInfo(packageName, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        @JavascriptInterface
        public void uninstallApp(String packageName) {
            Intent intent = new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + packageName));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openApp(String packageName) {
            Intent launchIntent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                mContext.startActivity(launchIntent);
            } else {
                Toast.makeText(mContext, "Cannot open app", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // apk download and install handler woah
    private void downloadAndInstallApk(final String apkUrl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String filename = URLUtil.guessFileName(apkUrl, null,
                            "application/vnd.android.package-archive");

                    isDownloading = true;
                    showNotification("Downloading", filename, true);

                    URL url = new URL(apkUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.connect();

                    if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        final int responseCode = conn.getResponseCode();
                        isDownloading = false;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this,
                                        "Download failed: " + responseCode,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                        cancelNotification();
                        return;
                    }

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    final File apkFile = new File(downloadsDir, filename);

                    InputStream in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(apkFile);

                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }

                    out.close();
                    in.close();
                    conn.disconnect();

                    isDownloading = false;
                    showNotification("Download complete", filename, false);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "Download complete: " + filename,
                                    Toast.LENGTH_SHORT).show();

                            if (!apkFile.exists()) return;

                            if (hasRootAccess()) {
                                Toast.makeText(MainActivity.this,
                                        "Found root! Auto installing...",
                                        Toast.LENGTH_LONG).show();
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        autoInstallWithRoot(apkFile);
                                    }
                                }).start();
                            } else {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setDataAndType(Uri.fromFile(apkFile),
                                        "application/vnd.android.package-archive");
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                            }

                            cancelNotification();
                        }
                    });

                } catch (final Exception e) {
                    isDownloading = false;
                    e.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "Download error: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                    cancelNotification();
                }
            }
        }).start();
    }

    // === Root helpers ===
    private boolean hasRootAccess() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private void autoInstallWithRoot(final File apkFile) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("pm install -r " + apkFile.getAbsolutePath() + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            final int result = process.waitFor();

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (result == 0) {
                        Toast.makeText(MainActivity.this,
                                "Auto install success!",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "Auto install failed (fallback).",
                                Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.fromFile(apkFile),
                                "application/vnd.android.package-archive");
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    }
                }
            });
        } catch (final Exception e) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this,
                            "Root install error: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // === Notification helpers ===
    private void showNotification(String title, String text, boolean ongoing) {
        if (Build.VERSION.SDK_INT >= 11) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(ongoing);

            Intent intent = new Intent(this, MainActivity.class);
            builder.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0));

            notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, builder.build());
        } else {
            Notification notification = new Notification(
                    android.R.drawable.stat_sys_download,
                    text,
                    System.currentTimeMillis());
            notification.flags = ongoing ? Notification.FLAG_ONGOING_EVENT : Notification.FLAG_AUTO_CANCEL;

            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

            try {
                Method m = Notification.class.getMethod("setLatestEventInfo",
                        Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                m.invoke(notification, this, title, text, pendingIntent);
            } catch (Exception ignored) { }

            notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, notification);
        }
    }

    private void cancelNotification() {
        notificationManager.cancel(DOWNLOAD_NOTIFICATION_ID);
    }
}
