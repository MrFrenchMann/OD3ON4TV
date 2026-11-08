package fr.canape.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Canapé : ouvre od3on.com en plein écran sur la Fire TV et la rend pilotable
 * à la télécommande (voir assets/tv.js pour la partie qui tourne dans la page).
 */
public class MainActivity extends Activity {

    static final String DEFAULT_URL = "https://www.od3on.com/";
    static final int[] ZOOMS = {67, 75, 80, 90, 100, 110, 125, 150};
    static final int DEFAULT_ZOOM_INDEX = 4; // 100 %

    // Couleurs du logo
    static final int LOGO_ORANGE = 0xFFCF623E;
    static final int LOGO_BLACK = 0xFF0F0E0C;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private FrameLayout root;
    private WebView web;
    private FrameLayout fullscreenHolder;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private CursorView cursor;
    private ProgressBar topProgress;
    private View splash;
    private View errorPanel;
    private TextView errorText;
    private Button retryButton;
    private View menuPanel;
    private Button firstMenuButton;
    private Button menuCursorButton;
    private TextView menuZoomLabel;

    private String tvJs = "";
    private boolean cursorMode;
    private int zoomIndex;
    private boolean firstPageShown;
    private long lastBackAt;
    private int modalBackCount;
    private long lastModalBackAt;
    private boolean centerLongHandled;

    /* ================= cycle de vie ================= */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        prefs = getSharedPreferences("canape", MODE_PRIVATE);
        cursorMode = prefs.getBoolean("cursor", false);
        zoomIndex = clampZoom(prefs.getInt("zoomIndex", DEFAULT_ZOOM_INDEX));
        tvJs = readAsset("tv.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        web = new WebView(this);
        setupWebView(web);
        root.addView(web, match());

        fullscreenHolder = new FrameLayout(this);
        fullscreenHolder.setBackgroundColor(Color.BLACK);
        fullscreenHolder.setVisibility(View.GONE);
        root.addView(fullscreenHolder, match());

        topProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        topProgress.setMax(100);
        topProgress.setProgressTintList(ColorStateList.valueOf(LOGO_ORANGE));
        topProgress.setVisibility(View.GONE);
        root.addView(topProgress, new FrameLayout.LayoutParams(MATCH, dp(4), Gravity.TOP));

        cursor = new CursorView(this);
        cursor.setVisibility(cursorMode ? View.VISIBLE : View.GONE);
        root.addView(cursor, match());

        splash = buildSplash();
        root.addView(splash, match());

        errorPanel = buildErrorPanel();
        errorPanel.setVisibility(View.GONE);
        root.addView(errorPanel, match());

        menuPanel = buildMenu();
        menuPanel.setVisibility(View.GONE);
        root.addView(menuPanel, new FrameLayout.LayoutParams(dp(340), MATCH, Gravity.START));

        web.requestFocus();
        web.loadUrl(startUrl());

        if (!prefs.getBoolean("helpShown", false)) {
            ui.postDelayed(() -> {
                if (isFinishing()) return;
                prefs.edit().putBoolean("helpShown", true).apply();
                showHelp();
            }, 2500);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) {
            // Coupe le son quand on revient à l'accueil de la Fire TV.
            web.evaluateJavascript("(function(){try{document.querySelectorAll('video,audio')"
                    + ".forEach(function(m){m.pause();});}catch(e){}})();", null);
            web.onPause();
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ================= WebView ================= */

    private void setupWebView(WebView w) {
        w.setBackgroundColor(Color.BLACK);
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setTextZoom(100);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);

        w.addJavascriptInterface(new Bridge(), "CanapeTV");
        w.setWebViewClient(new TvWebViewClient());
        w.setWebChromeClient(new TvChromeClient());
    }

    private class TvWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            inject();
            revealPage();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            inject();
            revealPage();
            topProgress.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showError(error.getErrorCode(), String.valueOf(error.getDescription()));
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            showError(errorCode, description);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Le moteur de la page a planté : on repart proprement au lieu de fermer l'appli.
            if (view == web) {
                root.removeView(web);
                web.destroy();
                web = null;
            }
            ui.post(MainActivity.this::recreate);
            return true;
        }
    }

    private class TvChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            if (!firstPageShown) return;
            topProgress.setProgress(progress);
            topProgress.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            fullscreenHolder.addView(view, match());
            fullscreenHolder.setVisibility(View.VISIBLE);
            if (web != null) web.setVisibility(View.INVISIBLE);
        }

        @Override
        public void onHideCustomView() {
            exitCustomView(false);
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            // Évite l'icône grise par défaut avant le démarrage d'une vidéo.
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    /** Appelé depuis la page (doit rester public pour que la WebView puisse l'appeler). */
    public final class Bridge {
        @JavascriptInterface
        public void setPlaying(final boolean playing) {
            ui.post(() -> {
                if (playing) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }
    }

    private void exitCustomView(boolean notifyPage) {
        if (customView == null) return;
        View v = customView;
        WebChromeClient.CustomViewCallback cb = customViewCallback;
        customView = null;
        customViewCallback = null;
        fullscreenHolder.removeView(v);
        fullscreenHolder.setVisibility(View.GONE);
        if (web != null) {
            web.setVisibility(View.VISIBLE);
            web.requestFocus();
        }
        if (notifyPage && cb != null) cb.onCustomViewHidden();
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                || scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript")) {
            return false;
        }
        Intent intent = null;
        try {
            intent = scheme.equals("intent")
                    ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            String fallback = intent == null ? null : intent.getStringExtra("browser_fallback_url");
            if (fallback != null && web != null) web.loadUrl(fallback);
            else toast("Ce lien ne peut pas s'ouvrir sur la TV.");
        } catch (Exception e) {
            toast("Ce lien ne peut pas s'ouvrir sur la TV.");
        }
        return true;
    }

    /* ================= pont avec la page (tv.js) ================= */

    private int zoom() {
        return ZOOMS[zoomIndex];
    }

    private String ensureScript() {
        return "if(!window.__tv){" + tvJs + "\n;}"
                + "if(window.__tv&&!window.__tvConfigured){window.__tvConfigured=1;"
                + "window.__tv.config(" + zoom() + "," + cursorMode + ");}";
    }

    private void inject() {
        if (web == null) return;
        web.evaluateJavascript("(function(){" + ensureScript()
                + "if(window.__tv)window.__tv.config(" + zoom() + "," + cursorMode + ");})();", null);
    }

    /** Exécute un appel sur window.__tv (en injectant le script s'il manque). */
    private void js(String call, ValueCallback<String> callback) {
        if (web == null) return;
        web.evaluateJavascript("(function(){" + ensureScript()
                + "return window.__tv?(" + call + "):null;})();", callback);
    }

    private static JSONObject parse(String json) {
        if (json == null || !json.startsWith("{")) return null;
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    /* ================= télécommande ================= */

    private static boolean isBackKey(int code) {
        return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B;
    }

    private static boolean isCenterKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        final int code = e.getKeyCode();
        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        // Menu de l'appli ouvert : navigation Android classique dans les boutons.
        if (menuPanel.getVisibility() == View.VISIBLE) {
            if (isBackKey(code) || code == KeyEvent.KEYCODE_MENU) {
                if (!down) hideMenu();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true; // le menu est une simple colonne : on garde le focus dedans
            }
            return super.dispatchKeyEvent(e);
        }

        // Écran « pas de connexion ».
        if (errorPanel.getVisibility() == View.VISIBLE) {
            if (isBackKey(code)) {
                if (!down) {
                    hideError();
                    if (web != null && web.canGoBack()) web.goBack(); else confirmExit();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                if (!down) showMenu();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        if (web == null) return super.dispatchKeyEvent(e);

        if (code == KeyEvent.KEYCODE_MENU) {
            if (!down) showMenu();
            return true;
        }
        if (isBackKey(code)) {
            if (!down) handleBack();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) onDirection(code, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) {
                    boolean seek = code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                            || code == KeyEvent.KEYCODE_MEDIA_REWIND;
                    int rc = e.getRepeatCount();
                    if (rc == 0 || (seek && rc % 4 == 0)) media(code);
                }
                return true;
            default:
                break;
        }
        if (isCenterKey(code)) {
            if (down) {
                if (e.isLongPress() && !centerLongHandled) {
                    centerLongHandled = true;
                    toggleCursor();
                }
            } else if (centerLongHandled) {
                centerLongHandled = false;
            } else if (e.getEventTime() - e.getDownTime() > 800) {
                toggleCursor(); // appui long sur une télécommande sans répétition de touche
            } else {
                activate();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void onDirection(int code, int repeat) {
        if (cursorMode) {
            moveCursor(code, repeat);
            return;
        }
        String dir;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: dir = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = "left"; break;
            default: dir = "right"; break;
        }
        js("window.__tv.move('" + dir + "')", null);
    }

    private void activate() {
        if (cursorMode) {
            cursor.placeIfNeeded(root.getWidth(), root.getHeight());
            tapAt(cursor.getPointerX(), cursor.getPointerY());
            return;
        }
        js("window.__tv.activate()", result -> {
            JSONObject o = parse(result);
            if (o == null || !o.optBoolean("tap", false)) return;
            View t = target();
            if (t == null) return;
            float x = (float) (o.optDouble("x", 0.5) * t.getWidth());
            float y = (float) (o.optDouble("y", 0.5) * t.getHeight());
            tapAt(x, y);
        });
    }

    private void handleBack() {
        if (customView != null) {
            exitCustomView(true);
            return;
        }
        js("window.__tv.back()", result -> {
            JSONObject o = parse(result);
            String state = o == null ? "none" : o.optString("state", "none");
            if (state.equals("fullscreen")) return;
            if (state.equals("modal")) {
                long now = SystemClock.uptimeMillis();
                modalBackCount = (now - lastModalBackAt < 4000) ? modalBackCount + 1 : 1;
                lastModalBackAt = now;
                if (modalBackCount == 1) {
                    sendKey(KeyEvent.KEYCODE_ESCAPE);
                    return;
                }
                if (modalBackCount == 2) {
                    js("window.__tv.closeModal()", null);
                    return;
                }
                modalBackCount = 0; // la fenêtre résiste : on revient à la page précédente
            } else {
                modalBackCount = 0;
            }
            if (web != null && web.canGoBack()) {
                web.goBack();
                return;
            }
            confirmExit();
        });
    }

    private void confirmExit() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBackAt < 2500) {
            finish();
            return;
        }
        lastBackAt = now;
        toast("Appuie encore sur Retour pour quitter");
    }

    private void media(int code) {
        final String action;
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY: action = "play"; break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: action = "pause"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: action = "ff"; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: action = "rw"; break;
            default: action = "toggle"; break;
        }
        js("window.__tv.media('" + action + "')", result -> {
            JSONObject o = parse(result);
            if (o == null || !o.optBoolean("iframe", false)) return;
            // Lecteur intégré d'un autre site : on lui envoie les touches clavier habituelles.
            if (action.equals("ff")) sendKey(KeyEvent.KEYCODE_DPAD_RIGHT);
            else if (action.equals("rw")) sendKey(KeyEvent.KEYCODE_DPAD_LEFT);
            else sendKey(KeyEvent.KEYCODE_SPACE);
        });
    }

    /* ================= gestes simulés ================= */

    private View target() {
        return customView != null ? customView : web;
    }

    private void tapAt(final float x, final float y) {
        final View t = target();
        if (t == null) return;
        final long downTime = SystemClock.uptimeMillis();
        MotionEvent downEv = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        t.dispatchTouchEvent(downEv);
        downEv.recycle();
        ui.postDelayed(() -> {
            MotionEvent upEv = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP, x, y, 0);
            upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            t.dispatchTouchEvent(upEv);
            upEv.recycle();
        }, 40);
    }

    private void hoverAt(float x, float y) {
        View t = target();
        if (t == null) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        t.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private void sendKey(int keyCode) {
        View t = target();
        if (t == null) return;
        long now = SystemClock.uptimeMillis();
        t.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        t.dispatchKeyEvent(new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, keyCode, 0));
    }

    /* ================= mode curseur ================= */

    private void moveCursor(int code, int repeat) {
        float step = dpf(10) + dpf(3) * Math.min(repeat, 22);
        float w = root.getWidth();
        float h = root.getHeight();
        cursor.placeIfNeeded(w, h);
        float x = cursor.getPointerX();
        float y = cursor.getPointerY();
        float nx = x;
        float ny = y;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: ny -= step; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: ny += step; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: nx -= step; break;
            default: nx += step; break;
        }
        float maxX = w - dpf(4);
        float maxY = h - dpf(4);
        float scrollX = 0f;
        float scrollY = 0f;
        if (ny < 0f) { ny = 0f; scrollY = -0.08f; }
        if (ny > maxY) { ny = maxY; scrollY = 0.08f; }
        if (nx < 0f) { nx = 0f; scrollX = -0.08f; }
        if (nx > maxX) { nx = maxX; scrollX = 0.08f; }
        cursor.setPointer(nx, ny);
        hoverAt(nx, ny);
        if ((scrollX != 0f || scrollY != 0f) && w > 0 && h > 0) {
            js("window.__tv.scrollAt(" + (nx / w) + "," + (ny / h) + "," + scrollX + "," + scrollY + ")", null);
        }
    }

    private void toggleCursor() {
        cursorMode = !cursorMode;
        prefs.edit().putBoolean("cursor", cursorMode).apply();
        cursor.setVisibility(cursorMode ? View.VISIBLE : View.GONE);
        cursor.placeIfNeeded(root.getWidth(), root.getHeight());
        js("window.__tv.config(" + zoom() + "," + cursorMode + ")", null);
        updateMenuLabels();
        toast(cursorMode
                ? "Mode curseur activé (OK maintenu pour revenir)"
                : "Mode télécommande activé");
    }

    /* ================= menu de l'appli ================= */

    private View buildMenu() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(26), dp(28), dp(26), dp(24));

        TextView title = label(getString(R.string.app_name), 28, true);
        col.addView(title);
        TextView hint = label("Menu — Retour pour fermer", 14, false);
        hint.setTextColor(0xFF9A9AA8);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(WRAP, WRAP);
        hp.bottomMargin = dp(14);
        col.addView(hint, hp);

        firstMenuButton = addButton(col, "Accueil", () -> {
            hideMenu();
            if (web != null) web.loadUrl(startUrl());
        });
        addButton(col, "Recharger la page", () -> {
            hideMenu();
            if (web != null) web.reload();
        });
        menuCursorButton = addButton(col, "", () -> {
            hideMenu();
            toggleCursor();
        });

        menuZoomLabel = label("", 15, false);
        menuZoomLabel.setTextColor(0xFFBFBFCC);
        LinearLayout.LayoutParams zp = new LinearLayout.LayoutParams(WRAP, WRAP);
        zp.topMargin = dp(12);
        zp.leftMargin = dp(4);
        col.addView(menuZoomLabel, zp);
        addButton(col, "Agrandir l'affichage", () -> changeZoom(1));
        addButton(col, "Réduire l'affichage", () -> changeZoom(-1));

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH, dp(1));
        sp.topMargin = dp(12);
        sp.bottomMargin = dp(8);
        View sep = new View(this);
        sep.setBackgroundColor(0x33FFFFFF);
        col.addView(sep, sp);

        addButton(col, "Adresse de départ", this::askStartUrl);
        addButton(col, "Aide de la télécommande", this::showHelp);
        addButton(col, "Quitter", this::finish);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF50C0B14, 0xE80C0B14}));
        scroll.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        updateMenuLabels();
        return scroll;
    }

    private void showMenu() {
        updateMenuLabels();
        menuPanel.setVisibility(View.VISIBLE);
        menuPanel.setTranslationX(-dpf(340));
        menuPanel.animate().translationX(0f).setDuration(180).start();
        firstMenuButton.requestFocus();
    }

    private void hideMenu() {
        menuPanel.setVisibility(View.GONE);
        if (web != null && customView == null) web.requestFocus();
    }

    private void updateMenuLabels() {
        if (menuCursorButton != null) {
            menuCursorButton.setText(cursorMode ? "Mode curseur : activé" : "Mode curseur : désactivé");
        }
        if (menuZoomLabel != null) {
            menuZoomLabel.setText("Taille de l'affichage : " + zoom() + " %");
        }
    }

    private void changeZoom(int delta) {
        zoomIndex = clampZoom(zoomIndex + delta);
        prefs.edit().putInt("zoomIndex", zoomIndex).apply();
        js("window.__tv.config(" + zoom() + "," + cursorMode + ")", null);
        updateMenuLabels();
    }

    private static int clampZoom(int i) {
        return Math.max(0, Math.min(ZOOMS.length - 1, i));
    }

    private String startUrl() {
        return prefs.getString("startUrl", DEFAULT_URL);
    }

    private void askStartUrl() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(startUrl());
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new FrameLayout.LayoutParams(MATCH, WRAP));

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Adresse de départ")
                .setMessage("Page ouverte au lancement et par « Accueil ».")
                .setView(box)
                .setPositiveButton("Enregistrer", (d, which) -> {
                    String url = normalizeUrl(input.getText().toString());
                    if (url == null) return;
                    prefs.edit().putString("startUrl", url).apply();
                    hideMenu();
                    if (web != null) web.loadUrl(url);
                })
                .setNeutralButton("od3on.com", (d, which) -> {
                    prefs.edit().remove("startUrl").apply();
                    hideMenu();
                    if (web != null) web.loadUrl(DEFAULT_URL);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static String normalizeUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return null;
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) s = "https://" + s;
        return s;
    }

    private void showHelp() {
        String msg = "Flèches : passer d'un élément à l'autre (il s'entoure de blanc)\n\n"
                + "OK : ouvrir, valider, écrire dans un champ\n\n"
                + "OK maintenu : mode curseur (une flèche de souris) marche / arrêt. "
                + "Pratique si un bouton n'est pas atteignable.\n\n"
                + "Retour : fermer une fenêtre, sortir du plein écran, page précédente. "
                + "Deux fois de suite sur la première page pour quitter.\n\n"
                + "Menu (≡) : accueil, recharger, taille de l'affichage, mode curseur\n\n"
                + "Lecture/Pause, Avance et Retour rapides : contrôle de la vidéo (±10 s)";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Télécommande")
                .setMessage(msg)
                .setPositiveButton("Compris", null)
                .show();
    }

    /* ================= écrans d'attente et d'erreur ================= */

    private View buildSplash() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(LOGO_BLACK);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lg = new LinearLayout.LayoutParams(dp(260), WRAP);
        lg.bottomMargin = dp(26);
        col.addView(logo, lg);
        String host = Uri.parse(startUrl()).getHost();
        TextView sub = label("Ouverture de " + (host == null ? "la page" : host.replaceFirst("^www\\.", "")) + "…", 18, false);
        sub.setTextColor(0xFFB4B0A8);
        col.addView(sub);
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(LOGO_ORANGE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.topMargin = dp(26);
        col.addView(pb, lp);
        f.addView(col, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        return f;
    }

    private void revealPage() {
        if (firstPageShown) return;
        firstPageShown = true;
        splash.animate().alpha(0f).setDuration(350).withEndAction(() -> splash.setVisibility(View.GONE)).start();
    }

    private View buildErrorPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(0xFF0B0B12);
        col.setPadding(dp(60), dp(40), dp(60), dp(40));
        errorText = label("", 20, false);
        errorText.setGravity(Gravity.CENTER);
        col.addView(errorText, new LinearLayout.LayoutParams(WRAP, WRAP));
        retryButton = makeButton("Réessayer", () -> {
            hideError();
            if (web != null) web.reload();
        });
        retryButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(240), dp(48));
        bp.topMargin = dp(28);
        col.addView(retryButton, bp);
        return col;
    }

    private void showError(int code, String description) {
        boolean network = code == WebViewClient.ERROR_HOST_LOOKUP
                || code == WebViewClient.ERROR_CONNECT
                || code == WebViewClient.ERROR_TIMEOUT
                || code == WebViewClient.ERROR_IO
                || code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE;
        if (!network) return;
        errorText.setText("Impossible d'ouvrir la page.\n"
                + "Vérifie que la Fire TV est bien connectée à Internet.\n\n(" + description + ")");
        splash.setVisibility(View.GONE);
        firstPageShown = true;
        errorPanel.setVisibility(View.VISIBLE);
        retryButton.requestFocus();
    }

    private void hideError() {
        errorPanel.setVisibility(View.GONE);
        if (web != null) web.requestFocus();
    }

    /* ================= petits outils d'interface ================= */

    private Button addButton(LinearLayout parent, String text, Runnable action) {
        Button b = makeButton(text, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(44));
        lp.topMargin = dp(4);
        parent.addView(b, lp);
        return b;
    }

    private Button makeButton(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.BLACK, Color.WHITE}));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(Color.WHITE));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        b.setBackground(bg);
        b.setFocusable(true);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpf(10));
        return g;
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(MATCH, MATCH);
    }

    private int dp(float v) {
        return Math.round(dpf(v));
    }

    private float dpf(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
