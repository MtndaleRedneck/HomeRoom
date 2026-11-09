package app.homeroom;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebStorage;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREFS = "homeroom";
    private static final String KEY_START_URL = "start_url";
    private static final String DEFAULT_PATH = "/integration/designed.html";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView web;
    private View setupView;
    private View errorView;
    private android.widget.TextView errorText;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        setupView = findViewById(R.id.setup);
        errorView = findViewById(R.id.error);
        web.setBackgroundColor(0xFF1E1D20);

        configureWebView();

        final EditText input = findViewById(R.id.setup_input);
        Button go = findViewById(R.id.setup_go);
        go.setOnClickListener(v -> applySetup(input.getText().toString()));
        input.setOnEditorActionListener((v, actionId, event) -> {
            applySetup(input.getText().toString());
            return true;
        });

        errorText = findViewById(R.id.error_text);
        Button retry = findViewById(R.id.error_retry);
        retry.setOnClickListener(v -> {
            String start = prefs.getString(KEY_START_URL, null);
            if (start == null) {
                showSetup();
            } else {
                loadStart(start);
            }
        });
        Button change = findViewById(R.id.error_change);
        change.setOnClickListener(v -> {
            prefs.edit().remove(KEY_START_URL).apply();
            web.stopLoading();
            web.clearHistory();
            web.clearCache(true);
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            showSetup();
        });

        String start = prefs.getString(KEY_START_URL, null);
        if (start == null) {
            showSetup();
        } else {
            loadStart(start);
        }

        UpdateCheck.checkInBackground(this);
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String start = prefs.getString(KEY_START_URL, null);
                if (start != null && sameOrigin(Uri.parse(start), uri)) {
                    return false;
                }
                openExternally(uri);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError(false);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame()) {
                    int code = errorResponse.getStatusCode();
                    if (code == 401 || code == 403) {
                        showError(true);
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // An expired or missing session is a page state ("Session ended"),
                // not a network error. Route it to the error screen so the owner
                // always has the "different setup link" way out.
                view.evaluateJavascript(
                        "(function(){var s=document.getElementById('status');return s?s.textContent:'';})()",
                        value -> {
                            if (value != null && value.toLowerCase().contains("session ended")) {
                                showError(true);
                            }
                        });
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Never bypass certificate errors.
                handler.cancel();
                showError(false);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(pick, null), FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) {
                    req.addRequestHeader("Cookie", cookie);
                }
                req.setMimeType(mimetype);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                openExternally(Uri.parse(url));
            }
        });
    }

    private void applySetup(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (!input.contains("://")) {
            input = "https://" + input;
        }
        Uri uri;
        try {
            uri = Uri.parse(input);
        } catch (Exception e) {
            uri = null;
        }
        if (uri == null || uri.getHost() == null || uri.getHost().isEmpty()) {
            Toast.makeText(this, R.string.setup_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            Toast.makeText(this, R.string.setup_https, Toast.LENGTH_LONG).show();
            return;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = DEFAULT_PATH;
        }
        String start = uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + path;
        StringBuilder firstLoad = new StringBuilder(start);
        String query = uri.getEncodedQuery();
        if (query != null && !query.isEmpty()) {
            firstLoad.append('?').append(query);
        }
        String fragment = uri.getEncodedFragment();
        if (fragment != null && !fragment.isEmpty()) {
            firstLoad.append('#').append(fragment);
        }
        prefs.edit().putString(KEY_START_URL, start).apply();
        // A fragment-only change on an already-loaded page is a same-document
        // navigation and the page never reads the new token. Force a full load.
        web.loadUrl("about:blank");
        loadStart(firstLoad.toString());
    }

    private void loadStart(String url) {
        show(R.id.web);
        web.loadUrl(url);
    }

    private void showSetup() {
        show(R.id.setup);
    }

    private void showError(boolean sessionEnded) {
        errorText.setText(sessionEnded ? R.string.error_session : R.string.error_title);
        show(R.id.error);
    }

    private void show(int id) {
        web.setVisibility(id == R.id.web ? View.VISIBLE : View.GONE);
        setupView.setVisibility(id == R.id.setup ? View.VISIBLE : View.GONE);
        errorView.setVisibility(id == R.id.error ? View.VISIBLE : View.GONE);
    }

    private static boolean sameOrigin(Uri a, Uri b) {
        if (a.getScheme() == null || b.getScheme() == null || a.getHost() == null || b.getHost() == null) {
            return false;
        }
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(Uri uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
