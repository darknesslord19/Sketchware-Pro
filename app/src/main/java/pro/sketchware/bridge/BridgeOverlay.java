package pro.sketchware.bridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.List;

/**
 * Diğer ekranların üstünde duran, sürüklenebilen robot. Dokununca durum ve işlem geçmişi paneli açılır.
 * Yalnızca kullanıcı açtıysa ve "üstte göster" izni verdiyse görünür. Tüm çağrılar ana iş parçacığındadır.
 */
final class BridgeOverlay {
    private static final String PREFS = "claude_bridge";
    private static final String KEY_ENABLED = "overlay";
    private static final String KEY_X = "overlay_x";
    private static final String KEY_Y = "overlay_y";
    private static final int SIZE_DP = 92;

    private static final int PANEL = 0xF52C2422;
    private static final int LINE = 0xFF3A302D;
    private static final int TEXT = 0xFFF1EBE8;
    private static final int DIM = 0xFFA79A95;
    private static final int ACCENT = 0xFF5CC8E0;
    private static final int GREEN = 0xFF34D399;
    private static final int RED = 0xFFF87171;

    @SuppressLint("StaticFieldLeak") // Yalnızca uygulama bağlamı tutulur.
    private static BridgeOverlay shown;

    private final Context context;
    private final WindowManager windows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout bubble;
    private final WebView robot;
    private final WindowManager.LayoutParams bubbleParams;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private TextView panelStatus;
    private TextView panelLog;
    private ScrollView panelScroll;
    private TextView connectButton;
    private TextView writeButton;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            robot.evaluateJavascript("window.skBot&&(skBot.mini(),skBot.update("
                    + JSONObject.quote(BridgeService.getRobotState()) + ","
                    + JSONObject.quote(BridgeService.getTask()) + "))", null);
            updatePanel();
            keepOnScreen();
            handler.postDelayed(this, 500);
        }
    };

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            sync(context);
        } else {
            remove();
        }
    }

    /** Tercih açık ve izin varsa robotu gösterir, değilse kaldırır. */
    static void sync(Context context) {
        if (!isEnabled(context) || !Settings.canDrawOverlays(context)) {
            remove();
        } else if (shown == null) {
            try {
                BridgeOverlay overlay = new BridgeOverlay(context.getApplicationContext());
                overlay.windows.addView(overlay.bubble, overlay.bubbleParams);
                overlay.handler.post(overlay.refresh);
                shown = overlay;
            } catch (RuntimeException e) {
                Toast.makeText(context, "Yüzen robot açılamadı: " + e.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Robot ayarları değişince sayfa yeniden yüklenir. */
    static void reloadRobot() {
        if (shown != null) {
            shown.robot.reload();
        }
    }

    private static void remove() {
        BridgeOverlay overlay = shown;
        if (overlay == null) {
            return;
        }
        shown = null;
        overlay.handler.removeCallbacks(overlay.refresh);
        overlay.closePanel();
        overlay.windows.removeView(overlay.bubble);
        overlay.robot.destroy();
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private BridgeOverlay(Context context) {
        this.context = context;
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        robot = new WebView(context);
        robot.setBackgroundColor(Color.TRANSPARENT);
        robot.setVerticalScrollBarEnabled(false);
        robot.setHorizontalScrollBarEnabled(false);
        robot.setOverScrollMode(View.OVER_SCROLL_NEVER);
        robot.getSettings().setJavaScriptEnabled(true);
        robot.getSettings().setDomStorageEnabled(true);
        robot.getSettings().setAllowFileAccess(false);
        robot.getSettings().setAllowContentAccess(false);
        robot.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("window.skBot&&skBot.mini()", null);
            }
        });
        robot.loadUrl("file:///android_asset/bridge/robot.html");

        // Dokunmaları üstteki boş görünüm alır; böylece sürükleme ve tıklama ayrılabilir.
        View touch = new View(context);
        touch.setContentDescription("Claude robotu");
        touch.setOnTouchListener(new Drag());

        bubble = new FrameLayout(context);
        bubble.addView(robot, new FrameLayout.LayoutParams(-1, -1));
        bubble.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        bubbleParams = params(dp(SIZE_DP), dp(SIZE_DP));
        bubbleParams.x = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_X, screen().widthPixels - dp(SIZE_DP));
        bubbleParams.y = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_Y, screen().heightPixels / 4);
    }

    private final class Drag implements View.OnTouchListener {
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragging;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(context).getScaledTouchSlop()) {
                        dragging = true;
                        closePanel();
                    }
                    if (dragging) {
                        move(startX + Math.round(dx), startY + Math.round(dy));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putInt(KEY_X, bubbleParams.x).putInt(KEY_Y, bubbleParams.y).apply();
                    } else {
                        view.performClick();
                        robot.evaluateJavascript("document.getElementById('gbot').click()", null);
                        if (panel == null) {
                            openPanel();
                        } else {
                            closePanel();
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    private void move(int x, int y) {
        DisplayMetrics screen = screen();
        bubbleParams.x = Math.max(0, Math.min(x, screen.widthPixels - dp(SIZE_DP)));
        bubbleParams.y = Math.max(0, Math.min(y, usableHeight() - dp(SIZE_DP)));
        windows.updateViewLayout(bubble, bubbleParams);
    }

    /** Ekran döndüğünde robot dışarıda kalmasın. */
    private void keepOnScreen() {
        DisplayMetrics screen = screen();
        if (bubbleParams.x > screen.widthPixels - dp(SIZE_DP)
                || bubbleParams.y > usableHeight() - dp(SIZE_DP)) {
            closePanel();
            move(bubbleParams.x, bubbleParams.y);
        }
    }

    private void openPanel() {
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(PANEL);
        shape.setCornerRadius(dp(16));
        shape.setStroke(dp(1), LINE);
        panel.setBackground(shape);

        panelStatus = label("", 15, TEXT);
        panelStatus.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(panelStatus);

        panelLog = label("", 12, DIM);
        panelScroll = new ScrollView(context);
        panelScroll.addView(panelLog);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, dp(130));
        scrollParams.topMargin = dp(8);
        panel.addView(panelScroll, scrollParams);

        connectButton = button("", ACCENT, v -> toggleConnection());
        writeButton = button("", DIM, v -> {
            if (!"off".equals(BridgeService.getRobotState())) {
                BridgeService.setWriteAllowed(!BridgeService.isWriteAllowed());
                updatePanel();
            } else {
                Toast.makeText(context, "Önce bağlan", Toast.LENGTH_SHORT).show();
            }
        });
        panel.addView(pair(connectButton, writeButton));
        panel.addView(pair(
                button("Ayar ekranı", TEXT, v -> {
                    closePanel();
                    openScreen();
                }),
                button("Robotu kapat", RED, v -> {
                    setEnabled(context, false);
                    Toast.makeText(context, "Robot, Claude bağlantısı ekranından tekrar açılır",
                            Toast.LENGTH_LONG).show();
                })));
        panel.addView(button("Paneli küçült  ▴", DIM, v -> closePanel()));

        DisplayMetrics screen = screen();
        int width = Math.min(dp(300), screen.widthPixels - dp(16));
        panelParams = params(width, WindowManager.LayoutParams.WRAP_CONTENT);
        // Metinler yazıldıktan sonra gerçek yükseklik ölçülür; tahmini yükseklik paneli robotun üstüne bindiriyordu.
        shownLog = null;
        updatePanel();
        int height = measuredHeight(width);
        int usable = usableHeight();
        // Panel ile robot ekrana birlikte sığmıyorsa (küçük ya da yatay ekran) işlem listesi kısaltılır.
        int excess = height + dp(SIZE_DP) - usable;
        boolean noSideRoom = (screen.widthPixels - dp(SIZE_DP)) / 2 < width;
        if (height > usable || (excess > 0 && noSideRoom)) {
            scrollParams.height = Math.max(dp(40), scrollParams.height - Math.max(excess, height - usable));
            height = measuredHeight(width);
        }
        // Robot sistemin sınırı dışında kaydedilmişse önce içeri alınır.
        move(bubbleParams.x, bubbleParams.y);
        int[] place = BridgePlacement.place(bubbleParams.x, bubbleParams.y, dp(SIZE_DP),
                width, height, screen.widthPixels, usable);
        panelParams.x = place[0];
        panelParams.y = place[1];
        if (place[2] != bubbleParams.y) {
            move(bubbleParams.x, place[2]);
        }
        windows.addView(panel, panelParams);
    }

    private int measuredHeight(int width) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return panel.getMeasuredHeight();
    }

    /** Pencere koordinatları durum çubuğunun altından başlar. */
    private int usableHeight() {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBar = id > 0 ? context.getResources().getDimensionPixelSize(id) : dp(24);
        return screen().heightPixels - statusBar;
    }

    private void closePanel() {
        if (panel != null) {
            windows.removeView(panel);
            panel = null;
        }
    }

    private String shownLog;

    private void updatePanel() {
        if (panel == null) {
            return;
        }
        boolean running = !"Kapalı".equals(BridgeService.getStatus());
        String state = BridgeService.getRobotState();
        panelStatus.setText("active".equals(state) ? "● Çalışıyor" : "idle".equals(state) ? "● Bağlı"
                : running ? "● Bağlanmaya çalışıyor" : "● Bağlantı kapalı");
        panelStatus.setTextColor("off".equals(state) ? DIM : GREEN);

        List<String> entries = BridgeService.getActivity();
        String text = entries.isEmpty() ? "Henüz işlem yok. Claude bir şey yaptığında burada görünür."
                : String.join("\n", entries);
        // Yalnızca yeni kayıt gelince en alta kayar; kullanıcı yukarıyı okurken ekran oynamaz.
        if (!text.equals(shownLog)) {
            shownLog = text;
            panelLog.setText(text);
            panelScroll.post(() -> panelScroll.fullScroll(View.FOCUS_DOWN));
        }
        connectButton.setText(running ? "Bağlantıyı kes" : "Bağlan");
        connectButton.setTextColor(running ? RED : ACCENT);
        boolean write = BridgeService.isWriteAllowed();
        writeButton.setText(write ? "Yazma: AÇIK" : "Yazma: kapalı");
        writeButton.setTextColor(write ? GREEN : DIM);
    }

    private void toggleConnection() {
        Intent service = new Intent(context, BridgeService.class);
        if (!"Kapalı".equals(BridgeService.getStatus())) {
            BridgeService.setWriteAllowed(false);
            context.stopService(service);
        } else if (BridgeLink.parse(BridgeService.getSavedLink(context)) == null) {
            closePanel();
            openScreen();
        } else {
            try {
                ContextCompat.startForegroundService(context, service);
            } catch (RuntimeException e) {
                closePanel();
                openScreen();
            }
        }
    }

    private void openScreen() {
        context.startActivity(new Intent(context, BridgeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private TextView button(String value, int color, View.OnClickListener listener) {
        TextView view = label(value, 13, color);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(42));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF1D1817);
        shape.setCornerRadius(dp(10));
        shape.setStroke(dp(1), LINE);
        view.setBackground(shape);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout pair(TextView first, TextView second) {
        LinearLayout row = new LinearLayout(context);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1f);
        left.topMargin = dp(6);
        left.setMarginEnd(dp(6));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1f);
        right.topMargin = dp(6);
        row.addView(first, left);
        row.addView(second, right);
        return row;
    }

    private static WindowManager.LayoutParams params(int width, int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    private DisplayMetrics screen() {
        return context.getResources().getDisplayMetrics();
    }

    private int dp(int value) {
        return Math.round(value * screen().density);
    }
}
