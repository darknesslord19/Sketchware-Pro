package pro.sketchware.activities.main.fragments.projects_store;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import mod.hey.studios.project.backup.BackupFactory;
import mod.hey.studios.project.backup.BackupRestoreManager;
import pro.sketchware.utility.SketchwareUtil;

/**
 * Store'dan dosyayı uygulama içinde indirir. Adres dosya yerine web sayfası döndürürse,
 * kullanıcıya sorup tarayıcıda açar. İndirilen hiçbir şey kendiliğinden kurulmaz ya da açılmaz.
 */
final class StoreDownloader {
    private static final long MAX_BYTES = 300L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 8;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy;

    private StoreDownloader() {
    }

    static void start(Activity activity, String url, String userAgent, String referer) {
        start(activity, url, userAgent, referer, null);
    }

    /** postBody verilirse ilk istek, sitenin indirme formundaki gibi POST olarak gönderilir. */
    static void start(Activity activity, String url, String userAgent, String referer, String postBody) {
        String scheme = Uri.parse(url).getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            SketchwareUtil.toast("Bu indirme türü uygulama içinde desteklenmiyor");
            return;
        }
        if (busy) {
            SketchwareUtil.toast("Başka bir indirme sürüyor");
            return;
        }
        busy = true;
        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog progress = new MaterialAlertDialogBuilder(activity)
                .setTitle("İndiriliyor")
                .setMessage("Bağlanılıyor…")
                .setCancelable(false)
                .setNegativeButton("İptal", (dialog, which) -> cancelled.set(true))
                .show();

        new Thread(() -> {
            File target = null;
            try {
                String current = url;
                String body = postBody;
                HttpURLConnection connection = null;
                for (int hop = 0; connection == null; hop++) {
                    if (hop > MAX_REDIRECTS) {
                        throw new IOException("Çok fazla yönlendirme");
                    }
                    if (!current.startsWith("https://")) {
                        // Şifresiz adresten dosya indirilmez.
                        String page = current;
                        finish(activity, progress, () -> askBrowser(activity, page));
                        return;
                    }
                    HttpURLConnection next = (HttpURLConnection) new URL(current).openConnection();
                    next.setInstanceFollowRedirects(false);
                    next.setConnectTimeout(20_000);
                    next.setReadTimeout(30_000);
                    next.setRequestProperty("User-Agent", userAgent);
                    if (referer != null) {
                        next.setRequestProperty("Referer", referer);
                    }
                    // Her adım yalnızca kendi alan adının çerezlerini alır.
                    String cookie = CookieManager.getInstance().getCookie(current);
                    if (cookie != null) {
                        next.setRequestProperty("Cookie", cookie);
                    }
                    if (body != null) {
                        Uri from = Uri.parse(url);
                        next.setRequestMethod("POST");
                        next.setDoOutput(true);
                        next.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        next.setRequestProperty("Origin", from.getScheme() + "://" + from.getHost());
                        try (OutputStream out = next.getOutputStream()) {
                            out.write(body.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    int code = next.getResponseCode();
                    // Çerez veren yanıtlar tarayıcıdaki gibi saklanır.
                    String setCookie = next.getHeaderField("Set-Cookie");
                    if (setCookie != null) {
                        CookieManager.getInstance().setCookie(current, setCookie);
                    }
                    if (code >= 300 && code < 400 && next.getHeaderField("Location") != null) {
                        String previous = current;
                        current = new URL(new URL(current), next.getHeaderField("Location")).toString();
                        // Form verisi yalnızca 307/308 ile ve aynı sunucuya yönlenirse yeniden gönderilir.
                        if ((code != 307 && code != 308) || !new URL(previous).getHost().equals(new URL(current).getHost())) {
                            body = null;
                        }
                        next.disconnect();
                    } else if (code == 200) {
                        connection = next;
                    } else {
                        next.disconnect();
                        throw new IOException("Sunucu hatası " + code);
                    }
                }

                String disposition = connection.getHeaderField("Content-Disposition");
                String type = String.valueOf(connection.getContentType()).toLowerCase(Locale.US);
                if (type.startsWith("text/html") && (disposition == null
                        || !disposition.toLowerCase(Locale.US).startsWith("attachment"))) {
                    connection.disconnect();
                    String page = current;
                    finish(activity, progress, postBody != null
                            ? () -> showPageInstead(activity)
                            : () -> askBrowser(activity, page));
                    return;
                }

                String name = StoreFileNames.choose(disposition, Uri.parse(current).getEncodedPath());
                File folder = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "Sketchub");
                if (!folder.isDirectory() && !folder.mkdirs()) {
                    throw new IOException("İndirme klasörü oluşturulamadı");
                }
                target = StoreFileNames.unused(folder, name);
                long length = connection.getContentLengthLong();
                if (length > MAX_BYTES) {
                    throw new IOException("Dosya çok büyük");
                }
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0;
                    long shown = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancelled.get()) {
                            throw new IOException("İptal edildi");
                        }
                        out.write(buffer, 0, read);
                        total += read;
                        if (total > MAX_BYTES) {
                            throw new IOException("Dosya çok büyük");
                        }
                        if (total - shown > 200 * 1024) {
                            shown = total;
                            String text = length > 0 ? "%" + (total * 100 / length) : (total / 1024) + " KB";
                            MAIN.post(() -> progress.setMessage(text));
                        }
                    }
                    if (length > 0 && total != length) {
                        throw new IOException("Dosya yarım indi");
                    }
                } finally {
                    connection.disconnect();
                }

                // Adı uzantısız gelen proje yedekleri de tanınır.
                boolean backup = target.getName().toLowerCase(Locale.US).endsWith("." + BackupFactory.EXTENSION);
                if (!backup && !target.getName().toLowerCase(Locale.US).endsWith(".apk")
                        && BackupFactory.zipContainsFile(target.getAbsolutePath(), "project")) {
                    File renamed = StoreFileNames.unused(folder, target.getName() + "." + BackupFactory.EXTENSION);
                    if (target.renameTo(renamed)) {
                        target = renamed;
                        backup = true;
                    }
                }
                File saved = target;
                boolean isBackup = backup;
                finish(activity, progress, () -> showDone(activity, saved, isBackup));
            } catch (Exception e) {
                if (target != null && target.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                }
                // Hata metninde adres bulunabileceği için yalnızca kısa açıklama gösterilir.
                String reason = cancelled.get() ? "İndirme iptal edildi"
                        : "İndirilemedi: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null && !e.getMessage().contains("://") ? " (" + e.getMessage() + ")" : "");
                finish(activity, progress, () -> SketchwareUtil.toast(reason));
            }
        }, "store-download").start();
    }

    private static void finish(Activity activity, AlertDialog progress, Runnable then) {
        MAIN.post(() -> {
            busy = false;
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            progress.dismiss();
            then.run();
        });
    }

    private static void showPageInstead(Activity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Dosya gelmedi")
                .setMessage("Site dosya yerine bir sayfa gönderdi. Sayfayı yenileyip "
                        + "\"Ben robot değilim\" kutusunu yeniden işaretle ve tekrar dene.")
                .setPositiveButton("Tamam", null)
                .show();
    }

    private static void askBrowser(Activity activity, String url) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Bu bir dosya değil")
                .setMessage("Bağlantı bir web sayfası açıyor: " + Uri.parse(url).getHost()
                        + "\n\nTarayıcıda açılsın mı?")
                .setPositiveButton("Aç", (dialog, which) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (RuntimeException e) {
                        SketchwareUtil.toast("Bağlantı açılamadı");
                    }
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private static void showDone(Activity activity, File file, boolean backup) {
        String where = "İndirilenler/Sketchub/" + file.getName();
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity).setTitle("İndirildi");
        if (backup) {
            dialog.setMessage(where + "\n\nProjelerine eklensin mi? Bu projeyi başkası hazırladı; "
                            + "derleyip kurmadan önce ne yaptığını kontrol et.")
                    .setPositiveButton("Projelere ekle", (d, which) ->
                            // Yedekle gelen yerel kütüphaneler kopyalanmaz.
                            new BackupRestoreManager(activity).doRestore(file.getAbsolutePath(), false))
                    .setNegativeButton("Sonra", null);
        } else {
            dialog.setMessage(where).setPositiveButton("Tamam", null);
        }
        dialog.show();
    }
}
