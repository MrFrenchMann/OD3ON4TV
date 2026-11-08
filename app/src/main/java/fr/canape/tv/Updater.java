package fr.canape.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Mise à jour de l'appli depuis GitHub : cherche la dernière version publiée
 * dans « Releases », télécharge canape.apk et lance l'installation.
 * Android demande toujours une confirmation finale (bouton « Installer »).
 */
final class Updater {

    private static final String APK_NAME = "canape.apk";

    private final MainActivity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private boolean busy;

    Updater(MainActivity activity) {
        act = activity;
    }

    static String currentVersion() {
        return BuildConfig.VERSION_NAME;
    }

    private static final class Release {
        int code;
        String name;
        String url;
        long size;
    }

    /* ---------- vérification silencieuse au lancement ---------- */

    void checkInBackground() {
        new Thread(() -> {
            try {
                final Release r = fetchLatest();
                if (r.code > BuildConfig.VERSION_CODE) {
                    ui.post(() -> {
                        if (!act.isFinishing()) act.onUpdateAvailable(r.name);
                    });
                }
            } catch (Exception ignored) {
                // pas de réseau ou GitHub injoignable : on réessaiera au prochain lancement
            }
        }, "canape-update-check").start();
    }

    /* ---------- mise à jour demandée depuis le menu ---------- */

    void checkAndInstall() {
        if (busy) return;
        busy = true;
        cancelled = false;
        final ProgressUi p = new ProgressUi(act, "Mise à jour",
                "Recherche d'une nouvelle version…", () -> cancelled = true);

        new Thread(() -> {
            Release found;
            try {
                found = fetchLatest();
            } catch (Exception e) {
                finish(p, "Impossible de vérifier les mises à jour.\n"
                        + "Vérifie que la Fire TV est connectée à Internet.\n\n(" + e.getMessage() + ")");
                return;
            }
            final Release r = found;
            if (cancelled) { finish(p, null); return; }
            if (r.code <= BuildConfig.VERSION_CODE) {
                finish(p, "Tu as déjà la dernière version (" + BuildConfig.VERSION_NAME + ").");
                return;
            }
            if (!canInstall()) {
                ui.post(() -> {
                    p.dismiss();
                    busy = false;
                    askInstallPermission();
                });
                return;
            }

            ui.post(() -> p.setMessage("Téléchargement de la version " + r.name + "…"));
            final File apk = new File(act.getCacheDir(), "mise-a-jour.apk");
            try {
                download(r, apk, p);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                finish(p, cancelled ? null : "Le téléchargement a échoué.\n\n(" + e.getMessage() + ")");
                return;
            }
            if (cancelled) { finish(p, null); return; }

            ui.post(() -> {
                p.setProgress(-1);
                p.setMessage("Préparation de l'installation…");
            });
            try {
                install(apk);
                ui.post(() -> {
                    p.dismiss();
                    busy = false;
                    Toast.makeText(act, "Confirme avec « Installer ». L'appli va se fermer : relance-la ensuite.",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                finish(p, "L'installation n'a pas pu démarrer.\n\n(" + e.getMessage() + ")");
            }
        }, "canape-update").start();
    }

    /** Réponse du système après le lancement de l'installation. */
    @SuppressWarnings("deprecation")
    void onInstallStatus(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            try {
                act.startActivity(confirm);
            } catch (Exception e) {
                showMessage("L'écran d'installation n'a pas pu s'ouvrir.\n\n(" + e.getMessage() + ")");
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(act, "Mise à jour installée.", Toast.LENGTH_SHORT).show();
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(act, "Mise à jour annulée.", Toast.LENGTH_SHORT).show();
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            showMessage("La mise à jour n'a pas été installée.\n\n(" + (msg == null ? "code " + status : msg) + ")");
        }
    }

    /* ---------- GitHub ---------- */

    private static Release fetchLatest() throws Exception {
        String json = readText(open("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest"));
        JSONObject o = new JSONObject(json);
        String tag = o.optString("tag_name", "");
        Release r = new Release();
        r.name = tag.startsWith("v") ? tag.substring(1) : tag;
        int dot = r.name.lastIndexOf('.');
        try {
            r.code = Integer.parseInt(r.name.substring(dot + 1));
        } catch (NumberFormatException e) {
            throw new IOException("numéro de version illisible : " + tag);
        }
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (APK_NAME.equals(a.optString("name"))) {
                    r.url = a.optString("browser_download_url", null);
                    r.size = a.optLong("size", 0);
                }
            }
        }
        if (r.url == null) throw new IOException(APK_NAME + " introuvable dans la version " + tag);
        return r;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CanapeTV/" + BuildConfig.VERSION_NAME);
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        return c;
    }

    private static String readText(HttpURLConnection c) throws IOException {
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new IOException("aucune version publiée (ou dépôt privé)");
            if (code != 200) throw new IOException("GitHub a répondu " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private void download(Release r, File out, ProgressUi p) throws IOException {
        HttpURLConnection c = open(r.url);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("GitHub a répondu " + code);
            long total = r.size > 0 ? r.size : c.getContentLength();
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                long lastUi = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new IOException("annulé");
                    os.write(buf, 0, n);
                    done += n;
                    long now = SystemClock.uptimeMillis();
                    if (now - lastUi > 150) {
                        lastUi = now;
                        final int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
                        ui.post(() -> p.setProgress(pct));
                    }
                }
            }
            if (r.size > 0 && out.length() != r.size) throw new IOException("fichier incomplet");
        } finally {
            c.disconnect();
        }
    }

    /* ---------- installation ---------- */

    private boolean canInstall() {
        if (Build.VERSION.SDK_INT < 26) return true; // Fire OS 5 et 6 : réglage « sources inconnues » global
        return act.getPackageManager().canRequestPackageInstalls();
    }

    private void install(File apk) throws IOException {
        PackageInstaller installer = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(act.getPackageName());
        params.setSize(apk.length());
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        boolean committed = false;
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("canape", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent callback = new Intent(act, MainActivity.class).setAction(MainActivity.ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getActivity(act, 42, callback, flags);
            session.commit(pending.getIntentSender());
            committed = true;
        } finally {
            if (committed) {
                session.close();
            } else {
                try { session.abandon(); } catch (Exception ignored) { /* déjà fermée */ }
            }
        }
    }

    private void askInstallPermission() {
        String name = act.getString(R.string.app_name);
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Une autorisation à donner (une seule fois)")
                .setMessage("Pour installer ses mises à jour, " + name
                        + " doit avoir le droit d'installer des applis.\n\n"
                        + "Paramètres > Ma Fire TV > Options pour les développeurs > "
                        + "Installer des applis inconnues > " + name + " : Activé\n\n"
                        + "Reviens ensuite dans Menu (≡) > Mettre à jour l'appli.")
                .setPositiveButton("Ouvrir les paramètres", (d, w) -> openInstallSettings())
                .setNegativeButton("Plus tard", null)
                .show();
    }

    private void openInstallSettings() {
        try {
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName())));
            return;
        } catch (Exception ignored) {
            // écran absent sur certaines Fire TV
        }
        try {
            act.startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(act, "Ouvre les Paramètres de la Fire TV avec la télécommande.", Toast.LENGTH_LONG).show();
        }
    }

    /* ---------- petits outils d'affichage ---------- */

    private void finish(ProgressUi p, String message) {
        ui.post(() -> {
            p.dismiss();
            busy = false;
            if (message != null) showMessage(message);
        });
    }

    private void showMessage(String message) {
        if (act.isFinishing()) return;
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Mise à jour")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Fenêtre avec un texte et une barre de progression. */
    private static final class ProgressUi {
        private final AlertDialog dialog;
        private final TextView text;
        private final ProgressBar bar;

        ProgressUi(Activity a, String title, String message, Runnable onCancel) {
            float d = a.getResources().getDisplayMetrics().density;
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(24 * d);
            col.setPadding(pad, Math.round(12 * d), pad, Math.round(8 * d));
            text = new TextView(a);
            text.setText(message);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            col.addView(text);
            bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setIndeterminate(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(16 * d);
            col.addView(bar, lp);
            dialog = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title)
                    .setView(col)
                    .setNegativeButton("Annuler", (dlg, w) -> onCancel.run())
                    .setCancelable(false)
                    .create();
            dialog.show();
        }

        void setMessage(String m) {
            text.setText(m);
        }

        void setProgress(int pct) {
            if (pct < 0) {
                bar.setIndeterminate(true);
            } else {
                bar.setIndeterminate(false);
                bar.setProgress(pct);
            }
        }

        void dismiss() {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Exception ignored) {
                // fenêtre déjà fermée avec l'appli
            }
        }
    }
}
