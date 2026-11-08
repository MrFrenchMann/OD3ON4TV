package fr.canape.tv;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.PlayerView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Lecteur vidéo / audio pensé pour la TV (comme Netflix) : plein écran, commandes à la
 * télécommande, image du podcast, image dans l'image. La page web lui confie la lecture,
 * ce qui évite l'écran noir et la page figée de la lecture dans le navigateur.
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_KIND = "kind";          // "video" ou "audio"
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_ARTWORK = "artwork";
    static final String EXTRA_POSITION = "position";  // en millisecondes
    static final String EXTRA_REFERER = "referer";
    static final String EXTRA_COOKIE = "cookie";
    static final String EXTRA_USER_AGENT = "ua";

    /** Résultat lu par l'écran principal à son retour (position atteinte, ou erreur). */
    static final class Result {
        long positionMs;
        long durationMs;
        String error;
        String url;
    }

    /** Prévenu dès que le lecteur se ferme, même si l'écran principal est en arrière-plan. */
    interface Listener {
        void onClosed(Result r);
    }

    static volatile Listener listener;
    private static volatile Result pending;
    static volatile String activeUrl;   // vidéo en cours dans le lecteur
    static boolean pipArmed;            // bouton « Image dans l'image » déjà utilisé

    static Result takeResult() {
        Result r = pending;
        pending = null;
        return r;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private PlayerView view;
    private MediaSession session;
    private View topBar;
    private TextView titleView;
    private Button pipButton;
    private String url;
    private boolean audioOnly;
    private boolean wasInPip;
    private boolean resultWritten;
    private DefaultTrackSelector trackSelector;
    private long lastFrames = -1;
    private int stallTicks;
    private int recoveries;

    /* ================= cycle de vie ================= */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        view = new PlayerView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_FIT);
        view.setShowNextButton(false);
        view.setShowPreviousButton(false);
        view.setShowShuffleButton(false);
        view.setControllerAutoShow(true);
        view.setKeepScreenOn(true);
        view.setFocusable(true);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildTopBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility ->
                topBar.setVisibility(isInPip() ? View.GONE : visibility));

        load(getIntent());
        view.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Nouvelle vidéo demandée alors que le lecteur était encore ouvert (petite fenêtre).
        resultWritten = false;
        load(intent);
    }

    private void load(Intent intent) {
        url = intent.getStringExtra(EXTRA_URL);
        if (url == null) {
            finish();
            return;
        }
        activeUrl = url;
        audioOnly = "audio".equals(intent.getStringExtra(EXTRA_KIND));
        String title = intent.getStringExtra(EXTRA_TITLE);
        titleView.setText(title == null || title.isEmpty() ? getString(R.string.app_name) : title);

        // Les en-têtes de la page (connexion, provenance) accompagnent chaque requête vidéo.
        Map<String, String> headers = new HashMap<>();
        String referer = intent.getStringExtra(EXTRA_REFERER);
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
            Uri r = Uri.parse(referer);
            if (r.getScheme() != null && r.getHost() != null) headers.put("Origin", r.getScheme() + "://" + r.getHost());
        }
        String cookie = intent.getStringExtra(EXTRA_COOKIE);
        if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        String ua = intent.getStringExtra(EXTRA_USER_AGENT);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(headers);
        if (ua != null) http.setUserAgent(ua);

        releasePlayer();
        recoveries = 0;
        stallTicks = 0;
        lastFrames = -1;
        // Qualité plafonnée à la Full HD : au-delà, une Fire TV Stick peine et l'image se fige.
        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(1920, 1080)
                .setMaxVideoFrameRate(60));
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(audioOnly ? C.AUDIO_CONTENT_TYPE_SPEECH : C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                fail(error.getErrorCodeName());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) close();
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (isInPip() && Build.VERSION.SDK_INT >= 26) {
                    try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) { }
                }
            }
        });

        MediaItem.Builder item = new MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(titleView.getText()).build());
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (lower.contains(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD);

        long start = intent.getLongExtra(EXTRA_POSITION, 0);
        player.setMediaItem(item.build(), Math.max(0, start));
        player.prepare();
        player.setPlayWhenReady(true);
        view.setPlayer(player);

        // Podcast : commandes toujours visibles, avec l'image du podcast en grand.
        view.setControllerShowTimeoutMs(audioOnly ? 0 : 3500);
        view.setDefaultArtwork(new ColorDrawable(0xFF0F0E0C));
        String artwork = intent.getStringExtra(EXTRA_ARTWORK);
        if (artwork != null && artwork.startsWith("http")) loadArtwork(artwork, referer, cookie);

        // Touches de la télécommande quand le lecteur est en petite fenêtre.
        try {
            if (session != null) session.release();
            session = new MediaSession.Builder(this, player).build();
        } catch (Exception e) {
            session = null;
        }
        view.showController();
        ui.removeCallbacks(watchdog);
        ui.postDelayed(watchdog, 4000);
    }

    /* ================= image figée : détection et relance ================= */

    // Toutes les 1,5 s : si la vidéo « joue » mais qu'aucune nouvelle image n'est affichée
    // pendant environ 4,5 s, on relance le décodage au même endroit, puis on baisse la qualité.
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            if (!audioOnly && player.isPlaying() && player.getPlaybackState() == Player.STATE_READY) {
                DecoderCounters dc = player.getVideoDecoderCounters();
                long frames = -1;
                if (dc != null) {
                    dc.ensureUpdated();
                    frames = dc.renderedOutputBufferCount;
                }
                if (frames == lastFrames) stallTicks++; else stallTicks = 0;
                lastFrames = frames;
                if (stallTicks >= 3) {
                    stallTicks = 0;
                    recoverFromStall();
                }
            } else {
                stallTicks = 0;
            }
            ui.postDelayed(this, 1500);
        }
    };

    private void recoverFromStall() {
        recoveries++;
        long pos = player.getCurrentPosition();
        if (recoveries == 2) {
            lowerQuality(1280, 720);
            Toast.makeText(this, "Qualité réduite pour une lecture fluide", Toast.LENGTH_SHORT).show();
        } else if (recoveries == 3) {
            lowerQuality(854, 480);
        } else if (recoveries > 4) {
            fail("IMAGE_FIGEE");
            return;
        }
        player.seekTo(pos); // vide le décodeur et repart du même moment
        player.play();
    }

    private void lowerQuality(int w, int h) {
        if (trackSelector == null) return;
        trackSelector.setParameters(trackSelector.buildUponParameters().setMaxVideoSize(w, h));
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Bouton Accueil pendant la lecture, après avoir déjà utilisé l'image dans l'image.
        if (pipArmed && player != null && player.isPlaying()) enterPip();
    }

    @Override
    protected void onResume() {
        super.onResume();
        wasInPip = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isInPip()) return;
        if (player != null) player.pause();
        // Petite fenêtre fermée : on referme le lecteur pour revenir à la page.
        if (wasInPip) close();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(watchdog);
        writeResult(null);
        if (url != null && url.equals(activeUrl)) activeUrl = null;
        if (session != null) {
            session.release();
            session = null;
        }
        releasePlayer();
        super.onDestroy();
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /* ================= télécommande ================= */

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (!down) close();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (!down) enterPip();
            return true;
        }
        if (player == null) return super.dispatchKeyEvent(e);
        boolean hidden = !view.isControllerFullyVisible();
        // Commandes cachées : OK = pause / lecture, gauche / droite = ±10 s, comme sur Netflix.
        if (hidden && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (!down) {
                if (player.isPlaying()) player.pause(); else player.play();
                view.showController();
            }
            return true;
        }
        if (hidden && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (down) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) player.seekBack(); else player.seekForward();
                view.showController();
            }
            return true;
        }
        // Touches Lecture/Pause, Avance et Retour rapides, quel que soit le bouton sélectionné.
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || code == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (view.dispatchMediaKeyEvent(e)) {
                if (down) view.showController();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /* ================= image dans l'image ================= */

    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= 24
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private boolean isInPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    private void enterPip() {
        if (!pipSupported()) {
            Toast.makeText(this, "Cette Fire TV ne gère pas l'image dans l'image.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isInPip()) return;
        pipArmed = true;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                enterPictureInPictureMode(pipParams());
            } else {
                enterPipLegacy();
            }
        } catch (Exception e) {
            Toast.makeText(this, "L'image dans l'image n'a pas pu s'ouvrir.", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void enterPipLegacy() {
        enterPictureInPictureMode();
    }

    private PictureInPictureParams pipParams() {
        int w = 16, h = 9;
        if (player != null && !audioOnly) {
            VideoSize vs = player.getVideoSize();
            if (vs.width > 0 && vs.height > 0) {
                w = vs.width;
                h = vs.height;
                float ratio = w / (float) h;
                if (ratio > 2.3f) { w = 23; h = 10; }
                if (ratio < 1f / 2.3f) { w = 10; h = 23; }
            }
        }
        return new PictureInPictureParams.Builder().setAspectRatio(new Rational(w, h)).build();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onPictureInPictureModeChanged(boolean inPip) {
        super.onPictureInPictureModeChanged(inPip);
        if (inPip) {
            wasInPip = true;
            view.setUseController(false);
            topBar.setVisibility(View.GONE);
        } else {
            view.setUseController(true);
            view.showController();
        }
    }

    /* ================= fin de lecture ================= */

    private void close() {
        writeResult(null);
        finish();
    }

    private void fail(String reason) {
        writeResult(reason == null ? "erreur" : reason);
        finish();
    }

    private void writeResult(String error) {
        if (resultWritten) return;
        resultWritten = true;
        final Result r = new Result();
        r.positionMs = player != null ? Math.max(0, player.getCurrentPosition()) : 0;
        long d = player != null ? player.getDuration() : C.TIME_UNSET;
        r.durationMs = d == C.TIME_UNSET ? 0 : d;
        r.error = error;
        r.url = url;
        final Listener l = listener;
        if (l != null) ui.post(() -> l.onClosed(r)); else pending = r;
    }

    /* ================= interface ================= */

    private View buildTopBar() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(32 * d);
        bar.setPadding(pad, Math.round(22 * d), pad, Math.round(40 * d));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000}));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(titleView);
        TextView hint = new TextView(this);
        hint.setTextColor(0xFFBBBBBB);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setText("OK : pause • ◀ ▶ : ±10 s • Menu (≡) : image dans l'image • Retour : quitter");
        texts.addView(hint);
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pipButton = new Button(this);
        pipButton.setText("Image dans l'image");
        pipButton.setAllCaps(false);
        pipButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        pipButton.setStateListAnimator(null);
        pipButton.setPadding(Math.round(18 * d), 0, Math.round(18 * d), 0);
        pipButton.setMinHeight(0);
        pipButton.setMinimumHeight(0);
        pipButton.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.BLACK, Color.WHITE}));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, pill(Color.WHITE, 0, d));
        bg.addState(new int[]{}, pill(0x66000000, 0x99FFFFFF, d));
        pipButton.setBackground(bg);
        pipButton.setOnClickListener(v -> enterPip());
        pipButton.setVisibility(pipSupported() ? View.VISIBLE : View.GONE);
        bar.addView(pipButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(44 * d)));

        topBar = bar;
        return bar;
    }

    private static GradientDrawable pill(int fill, int stroke, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(999f);
        if (stroke != 0) g.setStroke(Math.round(1.5f * d), stroke);
        return g;
    }

    /** Télécharge l'image du podcast (ou l'affiche de la vidéo) pour l'afficher en grand. */
    private void loadArtwork(final String src, final String referer, final String cookie) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                if (referer != null) c.setRequestProperty("Referer", referer);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                byte[] data;
                try (InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 12 * 1024 * 1024) throw new java.io.IOException("image trop lourde");
                    }
                    data = out.toByteArray();
                } finally {
                    c.disconnect();
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= 1080 || bounds.outHeight / (sample * 2) >= 1080) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                final Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                if (b != null) {
                    ui.post(() -> {
                        if (!isFinishing() && view != null) view.setDefaultArtwork(new BitmapDrawable(app.getResources(), b));
                    });
                }
            } catch (Throwable ignored) {
                // pas d'image (ou trop lourde) : fond noir
            }
        }, "odeon-artwork").start();
    }
}
