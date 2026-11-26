package pro.sketchware.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import pro.sketchware.R;

/**
 * Bağlantı açıkken köprü sunucusunu yoklayan ön plan hizmeti.
 * Bağlantıyı yalnızca kullanıcı başlatır; bildirimdeki düğmeyle kapatılabilir.
 */
public class BridgeService extends Service {
    private static final String PREFS = "claude_bridge";
    private static final String KEY_LINK = "link";
    private static final String ACTION_STOP = "pro.sketchware.bridge.STOP";
    private static final String CHANNEL = "claude_bridge";
    private static final int NOTIFICATION_ID = 7301;

    private static volatile String status = "Kapalı";
    /** Komut kısa sürse de robot "kullanımda" görünümünü bu süre boyunca korur. */
    private static final long ACTIVE_HOLD_MS = 4000;
    /** Sonuç yazısı robotun üzerinde okunabilecek kadar kalır. */
    private static final long RESULT_HOLD_MS = 12_000;
    private static volatile long activeUntil;
    private static volatile String task = "";
    /** Kalıcı değildir: bağlantı kapanınca ya da uygulama yeniden başlayınca kapanır. */
    private static volatile boolean writeAllowed;

    /** Yüzen robotta gösterilen son işlemler; yalnızca bellekte tutulur. */
    private static final int MAX_ACTIVITY = 40;
    private static final java.util.ArrayDeque<String> activity = new java.util.ArrayDeque<>();

    private volatile Thread worker;

    static java.util.List<String> getActivity() {
        synchronized (activity) {
            return new java.util.ArrayList<>(activity);
        }
    }

    private static void record(String line) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        synchronized (activity) {
            activity.addLast(time + "  " + line);
            while (activity.size() > MAX_ACTIVITY) {
                activity.removeFirst();
            }
        }
    }

    /** İşlemin hangi proje, dosya ya da kütüphane üzerinde olduğunu yazar. */
    private static String target(JSONObject command) {
        JSONObject args = command.optJSONObject("args");
        if (args == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String key : new String[]{"projectId", "path", "dependency", "name"}) {
            String value = args.optString(key);
            if (!value.isEmpty()) {
                text.append(" · ").append("projectId".equals(key) ? "#" : "").append(value);
            }
        }
        return text.toString();
    }

    static String getSavedLink(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LINK, "");
    }

    static void saveLink(Context context, String link) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LINK, link).apply();
    }

    static String getStatus() {
        return status;
    }

    /** Robotun durumu: "off", "idle" ya da "active". */
    static String getRobotState() {
        if (!status.startsWith("Bağlı")) {
            return "off";
        }
        return SystemClock.elapsedRealtime() < activeUntil ? "active" : "idle";
    }

    static boolean isWriteAllowed() {
        return writeAllowed;
    }

    static void setWriteAllowed(boolean allowed) {
        writeAllowed = allowed;
    }

    static String getTask() {
        return task;
    }

    private static String describe(JSONObject command) {
        JSONObject args = command.optJSONObject("args");
        switch (command.optString("action")) {
            case "list_projects":
                return "Projeleri listeliyorum";
            case "list_files":
                return "Dosyaları listeliyorum";
            case "read_file":
                String path = args == null ? "" : args.optString("path");
                return "Okuyorum: " + path.substring(path.lastIndexOf('/') + 1);
            case "compile_log":
                return "Derleme hatasını okuyorum";
            case "write_file":
                String target = args == null ? "" : args.optString("path");
                return "Yazıyorum: " + target.substring(target.lastIndexOf('/') + 1);
            case "delete_file":
                return "Dosya siliyorum (yedekli)";
            case "restore_backup":
                return "Yedeği geri yüklüyorum";
            case "list_backups":
                return "Yedekleri listeliyorum";
            case "create_project":
                return "Yeni proje oluşturuyorum";
            case "list_libraries":
                return "Kütüphanelere bakıyorum";
            case "download_library":
                return "Kütüphane indiriyorum";
            case "download_status":
                return "İndirmeyi kontrol ediyorum";
            case "add_libraries":
                return "Kütüphaneyi projeye ekliyorum";
            case "report_result":
                return succeeded(command) ? "İşlem başarılı ✓" : "İşlem tamamlanamadı";
            default:
                return "Çalışıyorum";
        }
    }

    private static boolean succeeded(JSONObject command) {
        JSONObject args = command.optJSONObject("args");
        return args != null && args.optBoolean("success", false);
    }

    /** Claude işi bitirdiğinde sonucu listenin en sonuna yazar ve ekranda gösterir. */
    private void announce(JSONObject command) {
        boolean success = succeeded(command);
        JSONObject args = command.optJSONObject("args");
        String summary = args == null ? "" : args.optString("summary").replaceAll("\\s+", " ").trim();
        if (summary.length() > 160) {
            summary = summary.substring(0, 160) + "…";
        }
        String message = success ? "İşlem başarılı, hazır Güven hocam"
                : "İşlem tamamlanamadı Güven hocam";
        // Özet önce yazılır; böylece sonuç satırı her zaman en sonda kalır.
        if (!summary.isEmpty()) {
            record("• " + summary);
        }
        record((success ? "✓ " : "✗ ") + message);
        activeUntil = SystemClock.elapsedRealtime() + RESULT_HOLD_MS;
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        BridgeOverlay.sync(this);
        BridgeLink link = BridgeLink.parse(getSavedLink(this));
        if (link == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null) {
            worker.interrupt();
        }
        status = "Bağlanıyor…";
        worker = new Thread(() -> pollLoop(link), "claude-bridge");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        status = "Kapalı";
        writeAllowed = false;
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Claude bağlantısı", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, BridgeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mtrl_settings)
                .setContentTitle("Claude bağlantısı açık")
                .setContentText("Claude projelerine erişebilir")
                .setOngoing(true)
                .addAction(0, "Kapat", stop)
                .build();
    }

    private boolean isCurrent() {
        return worker == Thread.currentThread() && !Thread.currentThread().isInterrupted();
    }

    private void report(String text) {
        if (isCurrent()) {
            status = text;
        }
    }

    private void pollLoop(BridgeLink link) {
        while (isCurrent()) {
            HttpURLConnection connection = null;
            try {
                connection = open(link.endpoint("poll"));
                connection.setReadTimeout(45_000);
                int code = connection.getResponseCode();
                // Beklerken bağlantı kapatıldıysa gelen komutu çalıştırma.
                if (!isCurrent()) {
                    return;
                }
                if (code == 204) {
                    report("Bağlı");
                } else if (code == 200) {
                    report("Bağlı, komut çalışıyor");
                    JSONObject command = new JSONObject(readText(connection.getInputStream()));
                    task = describe(command);
                    activeUntil = SystemClock.elapsedRealtime() + ACTIVE_HOLD_MS;
                    post(link.endpoint("result"), execute(command));
                    report("Bağlı");
                } else {
                    report("Sunucu hatası: " + code + (code == 404 ? " (adresi kontrol et)" : ""));
                    pause();
                }
            } catch (Exception e) {
                // Hata mesajı anahtarlı adresi içerebileceği için yalnızca türü gösterilir.
                report("Bağlantı yok (" + e.getClass().getSimpleName() + ")");
                pause();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private JSONObject execute(JSONObject command) throws Exception {
        JSONObject result = new JSONObject().put("id", command.getString("id"));
        try {
            JSONObject args = command.optJSONObject("args");
            String data = BridgeCommands.run(this, command.getString("action"),
                    args == null ? new JSONObject() : args, writeAllowed);
            if ("report_result".equals(command.optString("action"))) {
                announce(command);
            } else {
                record("✓ " + describe(command) + target(command));
            }
            return result.put("ok", true).put("data", data);
        } catch (Throwable e) {
            record("✗ " + describe(command) + target(command) + "\n      " + e.getMessage());
            return result.put("ok", false).put("error", String.valueOf(e.getMessage()));
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private static void post(String url, JSONObject body) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static String readText(InputStream stream) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    private static void pause() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
