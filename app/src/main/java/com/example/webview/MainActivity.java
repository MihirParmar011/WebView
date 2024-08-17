package com.example.webview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.print.PrintManager;
import android.util.Log;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String websiteURL = "https://tandavcreation.com/";
    private static final String signInURL = "https://tandavcreation.com/signin";
    private static final int FILECHOOSER_RESULTCODE = 1;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 2;
    private static final String COOKIES_PREFS = "CookiesPrefs";
    private static final String COOKIES_KEY = "CookiesKey";
    private WebView webView;
    private ValueCallback<Uri[]> mUploadMessage;
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private SwipeRefreshLayout swipeRefreshLayout;

    @RequiresApi(api = Build.VERSION_CODES.R)
    @SuppressLint({"MissingInflatedId", "SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.apni_webview);
        swipeRefreshLayout = findViewById(R.id.swipeContainer);

        swipeRefreshLayout.setOnRefreshListener(this::reloadWebView);

        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsets.Type.statusBars()).top;
            int navigationBarHeight = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            webView.setPadding(0, statusBarHeight, 0, navigationBarHeight);
            return insets;
        });

        if (!isInternetAvailable(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("No internet connection available")
                    .setMessage("Please check your mobile data or Wi-Fi network.")
                    .setPositiveButton("Ok", (dialog, which) -> finish())
                    .show();
        } else {
            WebSettings webSettings = webView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setAllowFileAccess(true);
            webSettings.setAllowContentAccess(true);
            webSettings.setDomStorageEnabled(true);

            // Load cookies before loading URL
            loadCookies();

            webView.setWebViewClient(new MyWebViewClient());
            webView.setWebChromeClient(new MyWebChromeClient());
            webView.loadUrl(websiteURL);

            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);

            startAutoRefresh();
            webView.addJavascriptInterface(new WebAppInterface(), "Android");
        }
        requestStoragePermission();
    }

    private class MyWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            swipeRefreshLayout.setRefreshing(false);
            saveCookies();
            refreshProfilePicture();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (isInternalUrl(url)) {
                if (url.equals(signInURL)) {
                    clearCookies(); // Clear cookies when navigating to the sign-in page
                }
                view.loadUrl(url);
            } else {
                openExternalAppOrBrowser(url);
            }
            return true;
        }

        private boolean isInternalUrl(String url) {
            return url.startsWith(websiteURL);
        }

        private void openExternalAppOrBrowser(String url) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        }
    }

    private class MyWebChromeClient extends WebChromeClient {
        public boolean onShowFileChooser(
                WebView webView, ValueCallback<Uri[]> filePathCallback,
                WebChromeClient.FileChooserParams fileChooserParams) {

            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(null);
            }
            mUploadMessage = filePathCallback;

            Intent intent = fileChooserParams.createIntent();
            try {
                startActivityForResult(intent, FILECHOOSER_RESULTCODE);
            } catch (android.content.ActivityNotFoundException ex) {
                // Handle error
            }
            return true;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (mUploadMessage == null) return;
            Uri result = data == null || resultCode != RESULT_OK ? null : data.getData();
            if (result != null) {
                mUploadMessage.onReceiveValue(new Uri[]{result});
            } else {
                mUploadMessage.onReceiveValue(null);
            }
            mUploadMessage = null;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST_CODE);
            }
        }
    }

    private boolean isInternetAvailable(Context context) {
        NetworkInfo info = ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private class WebAppInterface {
        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> doWebViewPrint());
        }

        @JavascriptInterface
        public void logout() {
            runOnUiThread(() -> {
                Log.d(TAG, "Logout called from JavaScript");
                clearCookies();  // Clear cookies on sign-out
            });
        }
    }

    private void doWebViewPrint() {
        PrintManager printManager = (PrintManager) this.getSystemService(PRINT_SERVICE);
        WebView webViewForPrint = new WebView(this);
        webViewForPrint.loadUrl(webView.getUrl());

        printManager.print("Invoice Print", webViewForPrint.createPrintDocumentAdapter(), null);
    }

    private void startAutoRefresh() {
        refreshHandler = new Handler();
        refreshRunnable = () -> {
            if (isInternetAvailable(MainActivity.this)) {
                webView.reload();
            }
            refreshHandler.postDelayed(refreshRunnable, 120000); // Refresh every 2 minutes
        };
        refreshHandler.post(refreshRunnable);
    }

    private void reloadWebView() {
        swipeRefreshLayout.setRefreshing(true);
        webView.reload();
    }

    private void refreshProfilePicture() {
        webView.evaluateJavascript("(function() { var img = document.getElementById('profile-pic'); if (img) { img.src = img.src.split('?')[0] + '?' + new Date().getTime(); } })();", null);
    }

    private void saveCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        String cookies = cookieManager.getCookie(websiteURL);
        if (cookies != null) {
            SharedPreferences prefs = getSharedPreferences(COOKIES_PREFS, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(COOKIES_KEY, cookies);
            editor.apply();
        }
    }

    private void loadCookies() {
        SharedPreferences prefs = getSharedPreferences(COOKIES_PREFS, MODE_PRIVATE);
        String cookies = prefs.getString(COOKIES_KEY, null);
        if (cookies != null) {
            CookieManager cookieManager = CookieManager.getInstance();
            String[] cookieArray = cookies.split(";");
            for (String cookie : cookieArray) {
                cookieManager.setCookie(websiteURL, cookie);
            }
            cookieManager.flush();
        }
    }

    private void clearCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(aBoolean -> {
            // Clear cookies from SharedPreferences after they are removed
            SharedPreferences prefs = getSharedPreferences(COOKIES_PREFS, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(COOKIES_KEY);
            editor.apply();
            // Reload the login page
            webView.loadUrl(signInURL);
        });
        cookieManager.flush();
    }
}
