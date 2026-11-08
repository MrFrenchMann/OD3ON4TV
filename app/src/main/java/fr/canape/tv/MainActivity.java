package fr.canape.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Rational;
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
import android.webkit.WebBackForwardList;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
 * Odeon : ouvre od3on.com en plein écran sur la Fire TV et la rend pilotable
 * à la télécommande (voir assets/tv.js pour la partie qui tourne dans la page).
 */
public class MainActivity extends Activity {

    static final String DEFAULT_URL = "https://www.od3on.com/";
    static final int[] ZOOMS = {67, 75, 80, 90, 100, 110, 125, 150};
    static final int DEFAULT_ZOOM_INDEX = 4; // 100 %
    static final String ACTION_INSTALL_STATUS = "fr.canape.tv.INSTALL_STATUS";

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
    private Button menuZoomButton;
    private Button menuPipButton;
    private Button menuUpdateButton;
    private Updater updater;
    private String availableUpdate;
    private boolean updateCheckDone;
    private boolean updatePromptShown;

    private String tvJs = "";
    private boolean cursorMode;
    private int zoomIndex;
    private boolean firstPageShown;
    private long lastBackAt;
    private final long[] backPresses = new long[3];
    private String lastBackFromUrl;
    private long lastBackNavAt;
    private int modalBackCount;
    private long lastModalBackAt;
    private boolean centerLongHandled;

    // Image dans l'image et lecture en arrière-plan
    private boolean pipArmed;        // l'utilisateur a déjà utilisé le bouton : Accueil garde la lecture
    private boolean enteringPip;
    private boolean mediaPlaying;
    private int pipBaseW;
    private int pipBaseH;
    private MediaSession mediaSession;

    // Lecteur TV : la page lui confie ses vidéos et ses sons
    private boolean nativePlayer;
    private Button menuPlayerButton;
    private volatile long lastUserActionAt;
    private volatile String manifestUrl;     // dernière liste de lecture vue (.m3u8 / .mpd)
    private volatile long manifestAt;
    private volatile String progressiveUrl;  // dernier fichier vidéo / audio vu (.mp4, .mp3…)
    private volatile long progressiveAt;
    private volatile String failedUrl;       // vidéo que le lecteur TV n'a pas su lire
    private String returnUrl;                // page à rouvrir au retour du lecteur TV
    private String returnKey;                // clé de la position gardée sur la TV
    private boolean pageBlanked;             // page vidée pendant la lecture (mémoire, décodeur)
    private PlayerActivity.Result pendingReport;
    private int reportTries;
    private boolean resumed;

    // Raccourci « Suivis » du menu
    private static final String FOLLOWING_NAMES = "['Suivis','Suivi','Mes suivis','Following','Followed']";
    private int followingAttempts;

    /* ================= cycle de vie ================= */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        prefs = getSharedPreferences("canape", MODE_PRIVATE);
        cursorMode = prefs.getBoolean("cursor", false);
        nativePlayer = prefs.getBoolean("nativePlayer", true);
        zoomIndex = clampZoom(prefs.getInt("zoomIndex", DEFAULT_ZOOM_INDEX));
        tvJs = readAsset("tv.js");
        installCrashRecorder();
        updater = new Updater(this);
        setupMediaSession();
        PlayerActivity.listener = this::onPlayerClosed;

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
        handleInstallIntent(getIntent());
        showLastCrash();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleInstallIntent(intent);
    }

    private void handleInstallIntent(Intent intent) {
        if (intent != null && ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            updater.onInstallStatus(intent);
        }
    }

    /** Appelé quand une version plus récente existe sur GitHub. */
    void onUpdateAvailable(String version) {
        availableUpdate = version;
        updateMenuLabels();
        if (updatePromptShown || isFinishing()) return;
        updatePromptShown = true;
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Nouvelle version d'" + getString(R.string.app_name))
                .setMessage("La version " + version + " est disponible.\n\nL'installer maintenant ? "
                        + "(Tu pourras aussi le faire plus tard depuis Menu \u2261 > Installer la mise à jour.)")
                .setPositiveButton("Installer", (x, w) -> updater.checkAndInstall())
                .setNegativeButton("Plus tard", null)
                .show();
        Button b = d.getButton(AlertDialog.BUTTON_POSITIVE);
        if (b != null) b.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (web != null) web.onResume();
        PlayerActivity.Result r = PlayerActivity.takeResult();
        if (r != null) {
            onPlayerClosed(r);
        } else if (pageBlanked && PlayerActivity.activeUrl != null) {
            // Le lecteur est en petite fenêtre et on revient sur l'appli : on rouvre la page.
            restorePage();
        }
    }

    /** Le lecteur TV vient de se fermer : on rouvre la page et on synchronise la position. */
    void onPlayerClosed(PlayerActivity.Result r) {
        if (r == null || isFinishing()) return;
        boolean failed = r.error != null;
        if (failed) {
            failedUrl = r.url;
            showPlayerError(r.error);
        }
        savePosition(returnKey, r.positionMs, r.durationMs);
        pendingReport = r;
        reportTries = 0;
        if (pageBlanked) restorePage();
        reportNow();
    }

    private void restorePage() {
        pageBlanked = false;
        if (web == null) return;
        if ("about:blank".equals(web.getUrl()) && web.canGoBack()) web.goBack();
        else if (returnUrl != null && "about:blank".equals(web.getUrl())) web.loadUrl(returnUrl);
    }

    private void reportNow() {
        PlayerActivity.Result r = pendingReport;
        if (r == null || web == null) return;
        // Page pas encore rouverte : on réessaie un peu plus tard.
        if (web.getProgress() < 100 || "about:blank".equals(web.getUrl())) {
            if (++reportTries < 30) ui.postDelayed(this::reportNow, 700);
            return;
        }
        pendingReport = null;
        reportTries = 0;
        // Appli en arrière-plan (bouton Accueil) : la page doit pouvoir lire pour qu'OD3ON
        // enregistre la position, puis on la remet en pause.
        if (!resumed) {
            web.onResume();
            ui.postDelayed(() -> {
                if (!resumed && web != null) web.onPause();
            }, 8000);
        }
        js("window.__tv.reportProgress(" + (r.positionMs / 1000.0) + "," + (r.error != null) + ")", null);
    }

    /* ================= position gardée sur la TV (secours) ================= */

    private static String positionKey(String page, String media) {
        String base = page != null && !page.isEmpty() ? page : media;
        if (base == null) return null;
        int hash = base.indexOf('#');
        if (hash >= 0) base = base.substring(0, hash);
        return "pos:" + Integer.toHexString(base.hashCode());
    }

    private void savePosition(String key, long posMs, long durationMs) {
        if (key == null) return;
        SharedPreferences sp = getSharedPreferences("positions", MODE_PRIVATE);
        boolean nearEnd = durationMs > 0 && posMs > durationMs - 30000;
        if (posMs < 10000 || nearEnd) sp.edit().remove(key).apply();
        else sp.edit().putLong(key, posMs).apply();
    }

    private long savedPosition(String key) {
        if (key == null) return 0;
        return getSharedPreferences("positions", MODE_PRIVATE).getLong(key, 0);
    }

    /* ================= erreurs (photo à envoyer pour corriger) ================= */

    private void showPlayerError(String code) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Le lecteur TV n'a pas pu lire ce contenu")
                .setMessage("La lecture repart dans la page.\n\nCode : " + code
                        + "\n\nSi ça se reproduit, prends cet écran en photo et envoie-le à Claude.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(sw));
                String text = sw.toString();
                if (text.length() > 1800) text = text.substring(0, 1800);
                getSharedPreferences("canape", MODE_PRIVATE).edit()
                        .putString("lastCrash", "Version " + BuildConfig.VERSION_NAME + "\n" + text).commit();
            } catch (Throwable ignored) {
                // rien de plus à faire
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private void showLastCrash() {
        final String crash = prefs.getString("lastCrash", null);
        if (crash == null) return;
        prefs.edit().remove("lastCrash").apply();
        ui.postDelayed(() -> {
            if (isFinishing()) return;
            TextView t = new TextView(this);
            t.setText(crash);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTextColor(0xFFDDDDDD);
            t.setPadding(dp(20), dp(8), dp(20), dp(8));
            ScrollView sv = new ScrollView(this);
            sv.addView(t);
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("L'appli s'est fermée la dernière fois")
                    .setMessage("Prends cet écran en photo et envoie-le à Claude pour qu'il corrige :")
                    .setView(sv)
                    .setPositiveButton("OK", null)
                    .show();
        }, 3000);
    }

    /* ================= lecteur TV ================= */

    /** Note au passage les adresses de vidéos et de sons que la page charge. */
    private void noteMediaUrl(String url) {
        if (url == null || !url.startsWith("http")) return;
        String path = url.toLowerCase();
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        long now = SystemClock.uptimeMillis();
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            // La première liste d'une série (la liste principale) est la bonne.
            if (now - manifestAt > 10000) {
                manifestUrl = url;
                manifestAt = now;
            }
        } else if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm")
                || path.endsWith(".mov") || path.endsWith(".mp3") || path.endsWith(".m4a")
                || path.endsWith(".aac") || path.endsWith(".ogg") || path.endsWith(".opus")
                || path.endsWith(".wav") || path.endsWith(".flac")) {
            if (now - progressiveAt > 10000 || !url.equals(progressiveUrl)) {
                progressiveUrl = url;
                progressiveAt = now;
            }
        }
    }

    /** Adresse à donner au lecteur TV, ou null si on ne la connaît pas. */
    private String playableUrl(String src) {
        long now = SystemClock.uptimeMillis();
        if (src != null && src.startsWith("http")) return src;
        String m = manifestUrl;
        if (m != null && now - manifestAt < 120000) return m;
        String p = progressiveUrl;
        if (p != null && now - progressiveAt < 120000) return p;
        return null;
    }

    /**
     * Appelé par la page au moment où une vidéo ou un son démarre. Renvoie « 1 » si le
     * lecteur TV prend le relais (la page ne lit alors rien elle-même).
     */
    private String handoffFromPage(String json) {
        if (!nativePlayer) return "0";
        try {
            JSONObject o = new JSONObject(json);
            boolean recent = SystemClock.uptimeMillis() - lastUserActionAt < 10000;
            // Aperçus muets qui se lancent tout seuls : on les laisse dans la page.
            if (!recent) return "0";
            if (o.optBoolean("muted", false) && "video".equals(o.optString("kind"))
                    && SystemClock.uptimeMillis() - lastUserActionAt > 4000) return "0";
            final String url = playableUrl(o.optString("src", ""));
            if (url == null || url.equals(failedUrl)) return "0";
            if (url.equals(PlayerActivity.activeUrl)) return "1";
            final String kind = o.optString("kind", "video");
            final String title = o.optString("title", "");
            final String artwork = o.optString("cover", "");
            final String page = o.optString("page", "");
            long pos = Math.round(o.optDouble("time", 0) * 1000);
            // OD3ON donne la position (reprise entre appareils) ; sinon, celle gardée sur la TV.
            final String key = positionKey(page, url);
            if (pos < 5000) pos = Math.max(pos, savedPosition(key));
            final long position = pos;
            ui.post(() -> {
                returnKey = key;
                startPlayer(url, kind, title, artwork, position, page);
            });
            return "1";
        } catch (Exception e) {
            return "0";
        }
    }

    private void startPlayer(String url, String kind, String title, String artwork, long position, String page) {
        if (isFinishing()) return;
        PlayerActivity.activeUrl = url;
        Intent i = new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_URL, url)
                .putExtra(PlayerActivity.EXTRA_KIND, kind)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
                .putExtra(PlayerActivity.EXTRA_ARTWORK, artwork)
                .putExtra(PlayerActivity.EXTRA_POSITION, position)
                .putExtra(PlayerActivity.EXTRA_REFERER, page)
                .putExtra(PlayerActivity.EXTRA_COOKIE, CookieManager.getInstance().getCookie(url))
                .putExtra(PlayerActivity.EXTRA_USER_AGENT, web != null ? web.getSettings().getUserAgentString() : null)
                // Ne pas déclencher l'image dans l'image de la page en ouvrant le lecteur.
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        try {
            startActivity(i);
        } catch (Exception e) {
            PlayerActivity.activeUrl = null;
            failedUrl = url;
            js("window.__tv.afterPlayer(0,true)", null);
            return;
        }
        // Pendant la lecture, la page est vidée : la Fire TV garde toute sa mémoire et son
        // décodeur vidéo pour le lecteur (c'est ce qui figeait l'image).
        if (web != null && !pageBlanked) {
            returnUrl = web.getUrl();
            pageBlanked = true;
            ui.postDelayed(() -> {
                if (pageBlanked && web != null) web.loadUrl("about:blank");
            }, 400);
        }
    }

    private void toggleNativePlayer() {
        nativePlayer = !nativePlayer;
        prefs.edit().putBoolean("nativePlayer", nativePlayer).apply();
        failedUrl = null;
        js(configCall(), null);
        updateMenuLabels();
        toast(nativePlayer ? "Les vidéos s'ouvrent dans le lecteur TV."
                : "Les vidéos restent dans la page du site.");
    }

    @Override
    protected void onPause() {
        resumed = false;
        // En image dans l'image, la vidéo ou le son continue : on ne coupe rien.
        if (web != null && !enteringPip && !isInPip()) stopPlayback();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Petite fenêtre fermée (ou appli quittée) : là, on coupe le son.
        if (web != null && !isInPip()) stopPlayback();
        super.onStop();
    }

    /** Coupe le son et met la page en pause (retour à l'accueil de la Fire TV). */
    private void stopPlayback() {
        web.evaluateJavascript("(function(){try{document.querySelectorAll('video,audio')"
                + ".forEach(function(m){m.pause();});if(window.__tv)window.__tv.media('pause');}catch(e){}})();", null);
        web.onPause();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Bouton Accueil pendant la lecture, après avoir utilisé l'image dans l'image :
        // la vidéo (ou le podcast) passe dans la petite fenêtre au lieu de s'arrêter.
        if (pipArmed && mediaPlaying && !isInPip()) startPip();
    }

    /* ================= image dans l'image ================= */

    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= 24
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private boolean isInPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    private String configCall() {
        return "window.__tv.config(" + zoom() + "," + cursorMode + "," + (pipSupported() && !nativePlayer) + "," + nativePlayer + ")";
    }

    /* ================= raccourci « Suivis » ================= */

    // Le menu se ferme et la page « Suivis » d'OD3ON s'ouvre en plein écran.
    private void openFollowing() {
        hideMenu();
        if (web == null) return;
        String known = prefs.getString("followingUrl", null);
        if (known != null) {
            web.loadUrl(known);
            return;
        }
        followingAttempts = 0;
        findFollowingTab(true);
    }

    /** Cherche l'onglet « Suivis » dans le menu du site et clique dessus. */
    private void findFollowingTab(final boolean firstTry) {
        js("window.__tv.openTab(" + FOLLOWING_NAMES + ")", result -> {
            JSONObject o = parse(result);
            if (o != null && o.optBoolean("found", false)) {
                followingAttempts = 0;
                String href = o.optString("href", "");
                // On retient l'adresse pour y aller directement la prochaine fois.
                if (href.startsWith("http") && !href.contains("#")) {
                    prefs.edit().putString("followingUrl", href).apply();
                }
                return;
            }
            if (firstTry) {
                // Pas sur cette page : on repasse par l'accueil du site.
                followingAttempts = 1;
                if (web != null) web.loadUrl(startUrl());
            } else if (followingAttempts > 0 && followingAttempts < 3) {
                followingAttempts++;
                ui.postDelayed(() -> findFollowingTab(false), 1500);
            } else {
                followingAttempts = 0;
                toast("Onglet « Suivis » introuvable. Connecte-toi d'abord à OD3ON, puis réessaie.");
            }
        });
    }

    /** Depuis le menu : seulement si une vidéo ou un son est présent. */
    private void startPipFromMenu() {
        hideMenu();
        if (!pipSupported()) {
            toast("Cette Fire TV ne gère pas l'image dans l'image.");
            return;
        }
        js("window.__tv.pipCheck()", result -> {
            JSONObject o = parse(result);
            if (o != null && o.optBoolean("media", false)) startPip();
            else toast("Lance d'abord une vidéo ou un podcast.");
        });
    }

    private void startPip() {
        if (!pipSupported()) {
            toast("Cette Fire TV ne gère pas l'image dans l'image.");
            return;
        }
        if (isInPip() || web == null) return;
        pipArmed = true;
        enteringPip = true;
        pipBaseW = root.getWidth();
        pipBaseH = root.getHeight();
        hideMenu();
        cursor.setVisibility(View.GONE);
        topProgress.setVisibility(View.GONE);
        freezeWebSize();
        // La page n'affiche plus que la vidéo, ou l'image du podcast.
        js("window.__tv.pipStart()", null);
        boolean ok = false;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ok = enterPictureInPictureMode(pipParams());
            } else {
                enterPictureInPictureModeLegacy();
                ok = true;
            }
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            enteringPip = false;
            leavePipLayout();
            toast("L'image dans l'image n'a pas pu s'ouvrir. Vérifie qu'elle est autorisée "
                    + "pour " + getString(R.string.app_name) + " dans les paramètres de la Fire TV.");
        }
    }

    @SuppressWarnings("deprecation")
    private void enterPictureInPictureModeLegacy() {
        enterPictureInPictureMode();
    }

    private PictureInPictureParams pipParams() {
        int w = pipBaseW > 0 ? pipBaseW : 16;
        int h = pipBaseH > 0 ? pipBaseH : 9;
        return new PictureInPictureParams.Builder().setAspectRatio(new Rational(w, h)).build();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onPictureInPictureModeChanged(boolean inPip) {
        super.onPictureInPictureModeChanged(inPip);
        enteringPip = false;
        if (inPip) {
            applyPipScale();
            updateMediaSession(mediaPlaying);
        } else {
            leavePipLayout();
        }
    }

    /** Retour en plein écran (ou fermeture) : la page retrouve son affichage normal. */
    private void leavePipLayout() {
        unfreezeWebSize();
        js("window.__tv.pipEnd()", null);
        cursor.setVisibility(cursorMode ? View.VISIBLE : View.GONE);
        if (web != null && customView == null) web.requestFocus();
        updateMediaSession(mediaPlaying);
    }

    // La page garde sa taille plein écran et est simplement réduite dans la petite fenêtre :
    // le site ne se réorganise pas et le lecteur n'est pas rechargé.
    private final View.OnLayoutChangeListener pipLayoutListener =
            (v, l, t, r, b, ol, ot, or, ob) -> applyPipScale();

    private void freezeWebSize() {
        if (pipBaseW <= 0 || pipBaseH <= 0) return;
        for (View v : new View[]{web, fullscreenHolder}) {
            if (v == null) continue;
            v.setLayoutParams(new FrameLayout.LayoutParams(pipBaseW, pipBaseH));
            v.setPivotX(0f);
            v.setPivotY(0f);
        }
        root.removeOnLayoutChangeListener(pipLayoutListener);
        root.addOnLayoutChangeListener(pipLayoutListener);
    }

    private void applyPipScale() {
        if (pipBaseW <= 0 || pipBaseH <= 0 || root.getWidth() <= 0) return;
        float s = Math.min(root.getWidth() / (float) pipBaseW, root.getHeight() / (float) pipBaseH);
        float tx = (root.getWidth() - pipBaseW * s) / 2f;
        float ty = (root.getHeight() - pipBaseH * s) / 2f;
        for (View v : new View[]{web, fullscreenHolder}) {
            if (v == null) continue;
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationX(tx);
            v.setTranslationY(ty);
        }
    }

    private void unfreezeWebSize() {
        root.removeOnLayoutChangeListener(pipLayoutListener);
        for (View v : new View[]{web, fullscreenHolder}) {
            if (v == null) continue;
            v.setLayoutParams(match());
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        }
    }

    /* ================= télécommande pendant l'image dans l'image ================= */

    // Pendant que la petite fenêtre est affichée, les touches Lecture/Pause, Avance et Retour
    // rapides de la télécommande arrivent ici.
    private void setupMediaSession() {
        try {
            mediaSession = new MediaSession(this, "Canape");
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { js("window.__tv.media('play')", null); }
                @Override public void onPause() { js("window.__tv.media('pause')", null); }
                @Override public void onStop() { js("window.__tv.media('pause')", null); }
                @Override public void onFastForward() { js("window.__tv.media('ff')", null); }
                @Override public void onRewind() { js("window.__tv.media('rw')", null); }
            });
            updateMediaSession(false);
        } catch (Exception e) {
            mediaSession = null;
        }
    }

    private void updateMediaSession(boolean playing) {
        if (mediaSession == null) return;
        try {
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                    .build();
            mediaSession.setPlaybackState(state);
            mediaSession.setActive(playing || isInPip());
        } catch (Exception ignored) {
            // session indisponible sur cet appareil
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (PlayerActivity.listener != null) PlayerActivity.listener = null;
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
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
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            noteMediaUrl(request.getUrl().toString()); // on regarde seulement, rien n'est bloqué
            return null;
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
            if (pendingReport != null && url != null && !url.startsWith("about:")) {
                ui.postDelayed(MainActivity.this::reportNow, 2500);
            }
            if (followingAttempts > 0) {
                // On vient de recharger l'accueil pour trouver l'onglet « Suivis » :
                // on laisse au site le temps d'afficher son menu.
                ui.postDelayed(() -> findFollowingTab(false), 1500);
            }
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
                mediaPlaying = playing;
                if (playing) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                updateMediaSession(playing);
            });
        }

        /** Bouton « Image dans l'image » posé sur le lecteur. */
        @JavascriptInterface
        public void enterPip() {
            ui.post(MainActivity.this::startPip);
        }

        /** Appui récent sur OK : une vidéo qui démarre maintenant a été demandée. */
        @JavascriptInterface
        public String recentAction() {
            return SystemClock.uptimeMillis() - lastUserActionAt < 10000 ? "1" : "0";
        }

        /** Une vidéo ou un son démarre dans la page : le lecteur TV prend-il le relais ? */
        @JavascriptInterface
        public String handoff(String json) {
            return handoffFromPage(json);
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
                + configCall() + ";}";
    }

    private void inject() {
        if (web == null) return;
        web.evaluateJavascript("(function(){" + ensureScript()
                + "if(window.__tv)" + configCall() + ";})();", null);
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
                if (down && menuZoomButton != null && menuZoomButton.hasFocus()) {
                    changeZoom(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                }
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
        lastUserActionAt = SystemClock.uptimeMillis();
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
        long now = SystemClock.uptimeMillis();
        // Trois appuis rapides sur Retour : on quitte, où que l'on soit.
        backPresses[0] = backPresses[1];
        backPresses[1] = backPresses[2];
        backPresses[2] = now;
        if (backPresses[0] != 0 && now - backPresses[0] < 2500) {
            finish();
            return;
        }
        if (customView != null) {
            exitCustomView(true);
            return;
        }
        // La page répond normalement tout de suite ; si elle est bloquée, on n'attend pas.
        final boolean[] handled = {false};
        ui.postDelayed(() -> {
            if (handled[0]) return;
            handled[0] = true;
            navigateBack();
        }, 700);
        js("window.__tv.back()", result -> {
            if (handled[0]) return;
            handled[0] = true;
            JSONObject o = parse(result);
            String state = o == null ? "none" : o.optString("state", "none");
            if (state.equals("fullscreen")) return;
            if (state.equals("modal")) {
                long t = SystemClock.uptimeMillis();
                modalBackCount = (t - lastModalBackAt < 4000) ? modalBackCount + 1 : 1;
                lastModalBackAt = t;
                if (modalBackCount == 1) {
                    sendKey(KeyEvent.KEYCODE_ESCAPE);
                    return;
                }
                if (modalBackCount == 2) {
                    js("window.__tv.closeModal()", null);
                    return;
                }
                // Cette fenêtre ne se ferme pas (élément permanent du site) : on l'ignore désormais.
                js("window.__tv.ignoreLayer()", null);
            }
            modalBackCount = 0;
            navigateBack();
        });
    }

    /** Page précédente, sans jamais rester coincé ; sur la page d'accueil, on propose de quitter. */
    private void navigateBack() {
        if (web == null || isAtStartPage()) {
            confirmExit();
            return;
        }
        long now = SystemClock.uptimeMillis();
        String current = web.getUrl();
        // Le retour précédent n'a rien changé (page qui renvoie toujours au même endroit).
        if (current != null && current.equals(lastBackFromUrl) && now - lastBackNavAt < 6000) {
            confirmExit();
            return;
        }
        WebBackForwardList list = web.copyBackForwardList();
        int index = list.getCurrentIndex();
        int steps = -1;
        // On saute les pages vides laissées par le lecteur TV.
        while (index + steps >= 0 && list.getItemAtIndex(index + steps) != null
                && "about:blank".equals(list.getItemAtIndex(index + steps).getUrl())) {
            steps--;
        }
        if (index + steps < 0) {
            confirmExit();
            return;
        }
        lastBackFromUrl = current;
        lastBackNavAt = now;
        web.goBackOrForward(steps);
    }

    private boolean isAtStartPage() {
        String u = web.getUrl();
        return u == null || "about:blank".equals(u) || samePage(u, startUrl()) || samePage(u, DEFAULT_URL);
    }

    private static boolean samePage(String a, String b) {
        return pageKey(a).equals(pageKey(b));
    }

    private static String pageKey(String u) {
        Uri p = Uri.parse(u == null ? "" : u);
        String host = p.getHost() == null ? "" : p.getHost().toLowerCase().replaceFirst("^www\\.", "");
        String path = p.getPath() == null ? "" : p.getPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return host + path;
    }

    private void confirmExit() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBackAt < 2500) {
            finish();
            return;
        }
        lastBackAt = now;
        toast("Appuie encore sur Retour pour quitter " + getString(R.string.app_name));
    }

    private void media(int code) {
        lastUserActionAt = SystemClock.uptimeMillis();
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
        js(configCall(), null);
        updateMenuLabels();
        toast(cursorMode
                ? "Mode curseur activé (OK maintenu pour revenir)"
                : "Mode télécommande activé");
    }

    /* ================= menu de l'appli ================= */

    private View buildMenu() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(22), dp(24), dp(18));

        TextView title = label(getString(R.string.app_name), 26, true);
        col.addView(title);
        TextView hint = label("Version " + Updater.currentVersion() + " \u2014 Retour pour fermer", 13, false);
        hint.setTextColor(0xFF9A9AA8);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(WRAP, WRAP);
        hp.bottomMargin = dp(10);
        col.addView(hint, hp);

        // Ordre pensé pour la TV : l'essentiel en haut, tout tient sur l'écran.
        firstMenuButton = addButton(col, "Accueil", () -> {
            hideMenu();
            if (web != null) web.loadUrl(startUrl());
        });
        addButton(col, "Suivis", this::openFollowing);
        menuUpdateButton = addButton(col, "", () -> {
            hideMenu();
            updater.checkAndInstall();
        });
        addButton(col, "Recharger la page", () -> {
            hideMenu();
            if (web != null) web.reload();
        });
        // Taille de l'affichage : ◀ ▶ pour changer, OK pour agrandir (revient au début après le maximum).
        menuZoomButton = addButton(col, "", () -> changeZoom(zoomIndex >= ZOOMS.length - 1 ? -zoomIndex : 1));
        menuPlayerButton = addButton(col, "", () -> {
            hideMenu();
            toggleNativePlayer();
        });
        menuCursorButton = addButton(col, "", () -> {
            hideMenu();
            toggleCursor();
        });
        menuPipButton = addButton(col, "Image dans l'image", this::startPipFromMenu);
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
        // Une mise à jour attend : on place directement la sélection dessus.
        if (availableUpdate != null) menuUpdateButton.requestFocus(); else firstMenuButton.requestFocus();
    }

    private void hideMenu() {
        menuPanel.setVisibility(View.GONE);
        if (web != null && customView == null) web.requestFocus();
    }

    private void updateMenuLabels() {
        if (menuCursorButton != null) {
            menuCursorButton.setText(cursorMode ? "Mode curseur : activé" : "Mode curseur : désactivé");
        }
        if (menuPlayerButton != null) {
            menuPlayerButton.setText(nativePlayer ? "Lecteur TV : activé" : "Lecteur TV : désactivé");
        }
        if (menuUpdateButton != null) {
            menuUpdateButton.setText(availableUpdate != null
                    ? "Installer la mise à jour " + availableUpdate
                    : "Mettre à jour l'appli");
        }
        if (menuZoomButton != null) {
            menuZoomButton.setText("\u25C0  Taille de l'affichage : " + zoom() + "\u00A0%  \u25B6");
        }
        if (menuPipButton != null) {
            // Avec le lecteur TV, l'image dans l'image se lance depuis le lecteur (Menu \u2261).
            menuPipButton.setVisibility(nativePlayer ? View.GONE : View.VISIBLE);
        }
    }

    private void changeZoom(int delta) {
        zoomIndex = clampZoom(zoomIndex + delta);
        prefs.edit().putInt("zoomIndex", zoomIndex).apply();
        js(configCall(), null);
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
                + "Deux fois de suite sur l'accueil pour quitter, ou trois appuis rapides n'importe où.\n\n"
                + "Menu (\u2261) : accueil, Suivis, image dans l'image, recharger, taille de l'affichage, "
                + "mode curseur, mise à jour\n\n"
                + "Lecture/Pause, Avance et Retour rapides : contrôle de la vidéo ou du son (\u00B110\u00A0s), "
                + "même dans la petite fenêtre\n\n"
                + "Image dans l'image : bouton posé sur le lecteur. Ensuite, le bouton Accueil "
                + "garde la lecture dans une petite fenêtre. Pour revenir en grand, rouvre l'appli.";
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
        if (!updateCheckDone) {
            // Une fois la page affichée, on regarde discrètement s'il existe une nouvelle version.
            updateCheckDone = true;
            ui.postDelayed(() -> {
                if (!isFinishing()) updater.checkInBackground();
            }, 6000);
        }
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(38));
        lp.topMargin = dp(3);
        parent.addView(b, lp);
        return b;
    }

    private Button makeButton(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
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
