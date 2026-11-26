package pro.sketchware.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.besome.sketch.lib.base.BaseAppCompatActivity;

import org.json.JSONObject;

import pro.sketchware.utility.SketchwareUtil;

/** Köprü adresinin girildiği ve bağlantının açılıp kapatıldığı ekran. */
public class BridgeActivity extends BaseAppCompatActivity {
    private static final int BACKGROUND = 0xFF231D1B;
    private static final int CARD = 0xFF2C2422;
    private static final int FIELD = 0xFF1D1817;
    private static final int LINE = 0xFF3A302D;
    private static final int TEXT = 0xFFF1EBE8;
    private static final int DIM = 0xFFA79A95;
    private static final int ACCENT = 0xFF5CC8E0;
    private static final int ACCENT_END = 0xFF8AA4E8;
    private static final int ON_ACCENT = 0xFF0B1519;
    private static final int GREEN = 0xFF34D399;
    private static final int AMBER = 0xFFFBBF24;
    private static final int RED = 0xFFF87171;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView robot;
    private View body;
    private View statusDot;
    private TextView statusTitle;
    private TextView statusDetail;
    private EditText input;
    private Button connectButton;
    private Switch allowWrite;
    private Switch floating;
    /** İzin ekranına gidildiyse, dönüşte robot kendiliğinden açılır. */
    private boolean awaitingOverlayPermission;
    private Boolean shownRunning;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            showStatus(BridgeService.getStatus());
            // Robot panelden kapatılırsa düğme de güncellenir.
            boolean overlayOn = BridgeOverlay.isEnabled(BridgeActivity.this)
                    && Settings.canDrawOverlays(BridgeActivity.this);
            if (floating.isChecked() != overlayOn) {
                floating.setChecked(overlayOn);
            }
            robot.evaluateJavascript("window.skBot&&skBot.update("
                    + JSONObject.quote(BridgeService.getRobotState()) + ","
                    + JSONObject.quote(BridgeService.getTask()) + ")", null);
            handler.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout content = column();
        content.setPadding(dp(16), dp(4), dp(16), dp(24));
        content.addView(createStatusCard(), spaced());
        content.addView(createLinkCard(), spaced());

        connectButton = new Button(this);
        connectButton.setOnClickListener(v -> toggleConnection());
        content.addView(connectButton, spaced(52));

        content.addView(createPermissionCard(), spaced());
        content.addView(createOverlayCard(), spaced());
        content.addView(createPrivacyCard(), spaced());

        Button robotSettings = new Button(this);
        style(robotSettings, "Robot ayarları", ACCENT, background(Color.TRANSPARENT, LINE));
        robotSettings.setOnClickListener(v -> {
            showRobotFullScreen(true);
            robot.evaluateJavascript("window.skBot&&skBot.edit()", null);
        });
        content.addView(robotSettings, spaced(48));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        body = scroll;

        robot = createRobot();
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setFitsSystemWindows(true);
        root.addView(createHeader());
        root.addView(robot);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        showRobotFullScreen(false);
        setContentView(root);
    }

    private View createHeader() {
        LinearLayout header = row();
        header.setPadding(dp(4), dp(4), dp(16), dp(4));
        TextView back = text("←", 24, TEXT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Geri");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(bold(text("Claude bağlantısı", 20, TEXT)));
        return header;
    }

    private View createStatusCard() {
        LinearLayout card = card();
        LinearLayout line = row();
        statusDot = new View(this);
        LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(dp(10), dp(10));
        dot.setMarginEnd(dp(10));
        line.addView(statusDot, dot);
        statusTitle = bold(text("", 17, TEXT));
        line.addView(statusTitle);
        statusDetail = text("", 13, DIM);
        statusDetail.setPadding(0, dp(6), 0, 0);
        card.addView(line);
        card.addView(statusDetail);
        return card;
    }

    private View createLinkCard() {
        LinearLayout card = card();
        card.addView(bold(text("Köprü adresi", 15, TEXT)));

        LinearLayout line = row();
        line.setBackground(rounded(FIELD, LINE));
        line.setPadding(dp(12), 0, 0, 0);

        input = new EditText(this);
        input.setHint("https://sunucun.onrender.com/mcp/ANAHTAR");
        input.setHintTextColor(DIM);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(BridgeService.getSavedLink(this));
        line.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView reveal = text("Göster", 13, ACCENT);
        reveal.setGravity(Gravity.CENTER);
        reveal.setPadding(dp(12), 0, dp(12), 0);
        reveal.setOnClickListener(v -> {
            boolean hidden = (input.getInputType() & InputType.TYPE_MASK_VARIATION)
                    == InputType.TYPE_TEXT_VARIATION_PASSWORD;
            input.setInputType(InputType.TYPE_CLASS_TEXT | (hidden
                    ? InputType.TYPE_TEXT_VARIATION_URI : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            input.setSelection(input.length());
            reveal.setText(hidden ? "Gizle" : "Göster");
        });
        line.addView(reveal, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        card.addView(line, params);

        TextView note = text("Claude'a bağlayıcı olarak eklediğin adresin aynısı. Kimseyle paylaşma.", 12, DIM);
        note.setPadding(0, dp(8), 0, 0);
        card.addView(note);
        return card;
    }

    private View createPermissionCard() {
        LinearLayout card = card();
        allowWrite = new Switch(this);
        allowWrite.setText("Yazmaya izin ver");
        allowWrite.setTextColor(TEXT);
        allowWrite.setTextSize(15);
        allowWrite.setTypeface(Typeface.DEFAULT_BOLD);
        allowWrite.setThumbTintList(switchColors(GREEN, 0xFFB8C0CF));
        allowWrite.setTrackTintList(switchColors(0x8034D399, 0x66707784));
        allowWrite.setChecked(BridgeService.isWriteAllowed());
        allowWrite.setOnCheckedChangeListener((button, checked) -> BridgeService.setWriteAllowed(checked));
        card.addView(allowWrite);
        TextView note = text("Claude proje oluşturabilir, kütüphane indirebilir, dosya yazıp silebilir. "
                + "Proje değişikliklerinden önce yedek alınır. Bağlantı kapanınca izin de kapanır.", 12, DIM);
        note.setPadding(0, dp(8), 0, 0);
        card.addView(note);
        return card;
    }

    private View createOverlayCard() {
        LinearLayout card = card();
        floating = new Switch(this);
        floating.setText("Yüzen robot");
        floating.setTextColor(TEXT);
        floating.setTextSize(15);
        floating.setTypeface(Typeface.DEFAULT_BOLD);
        floating.setThumbTintList(switchColors(GREEN, 0xFFB8C0CF));
        floating.setTrackTintList(switchColors(0x8034D399, 0x66707784));
        floating.setChecked(BridgeOverlay.isEnabled(this) && Settings.canDrawOverlays(this));
        floating.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !Settings.canDrawOverlays(this)) {
                button.setChecked(false);
                awaitingOverlayPermission = true;
                SketchwareUtil.toast("\"Diğer uygulamaların üzerinde göster\" iznini aç");
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                BridgeOverlay.setEnabled(this, checked);
            }
        });
        card.addView(floating);
        TextView note = text("Robot diğer ekranların üstünde durur; sürükleyip taşıyabilirsin. Dokununca "
                + "yapılan işlemler ve bağlantı düğmeleri açılır.", 12, DIM);
        note.setPadding(0, dp(8), 0, 0);
        card.addView(note);
        return card;
    }

    private View createPrivacyCard() {
        LinearLayout card = card();
        TextView title = bold(text("Güvenlik ve gizlilik  ▾", 15, TEXT));
        TextView details = text("• Bağlantı açıkken Claude proje dosyalarını ve derleme hatalarını okuyabilir. "
                + "Dosyalardaki API anahtarları da buna dahildir.\n\n"
                + "• Yedekler .sketchware/claude_backups klasöründe durur.\n\n"
                + "• Yazılan kod, derleyip kurduğun uygulamada çalışır. Kurmadan önce ne yaptığını bil.\n\n"
                + "• Değiştirilen proje düzenleyicide kapalı olmalı.\n\n"
                + "• İşin bitince bağlantıyı kapat.", 13, DIM);
        details.setPadding(0, dp(10), 0, 0);
        details.setVisibility(View.GONE);
        card.addView(title);
        card.addView(details);
        card.setOnClickListener(v -> {
            boolean open = details.getVisibility() != View.VISIBLE;
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            title.setText(open ? "Güvenlik ve gizlilik  ▴" : "Güvenlik ve gizlilik  ▾");
        });
        return card;
    }

    private static boolean isRunning() {
        return !"Kapalı".equals(BridgeService.getStatus());
    }

    private void toggleConnection() {
        if (isRunning()) {
            allowWrite.setChecked(false);
            stopService(new Intent(this, BridgeService.class));
            return;
        }
        String link = input.getText().toString().trim();
        if (BridgeLink.parse(link) == null) {
            SketchwareUtil.toast("Adres https://sunucu/mcp/ANAHTAR biçiminde olmalı");
            return;
        }
        BridgeService.saveLink(this, link);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        ContextCompat.startForegroundService(this, new Intent(this, BridgeService.class));
    }

    /** Hizmetin teknik durum yazısını anlaşılır başlık ve açıklamaya çevirir. */
    private void showStatus(String status) {
        String title;
        String detail;
        int color;
        if (status.startsWith("Bağlı")) {
            title = "Bağlı";
            detail = "Claude komut gönderebilir.";
            color = GREEN;
        } else if (status.startsWith("Bağlanıyor")) {
            title = "Bağlanıyor…";
            detail = "Bu işlem yarım dakika kadar sürebilir.";
            color = AMBER;
        } else if (status.startsWith("Sunucu hatası")) {
            title = "Sunucu hatası";
            detail = status.contains("404") ? "Adres ya da anahtar sunucudakiyle eşleşmiyor."
                    : status + ". Otomatik olarak tekrar deneniyor.";
            color = RED;
        } else if (status.startsWith("Bağlantı yok")) {
            title = "Sunucuya ulaşılamıyor";
            if (status.contains("SocketTimeout")) {
                detail = "Sunucu zamanında yanıt vermedi; uyanıyor ya da yeniden başlıyor olabilir. "
                        + "Otomatik olarak tekrar deneniyor.";
            } else if (status.contains("UnknownHost")) {
                detail = "İnternet bağlantını ve sunucu adresini kontrol et. Tekrar deneniyor.";
            } else {
                detail = status + ". Otomatik olarak tekrar deneniyor.";
            }
            color = RED;
        } else {
            title = "Bağlantı kapalı";
            detail = "Başlamak için Bağlan'a dokun.";
            color = DIM;
        }
        statusTitle.setText(title);
        statusDetail.setText(detail);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusDot.setBackground(dot);

        boolean running = isRunning();
        if (shownRunning == null || shownRunning != running) {
            shownRunning = running;
            if (running) {
                style(connectButton, "Bağlantıyı kapat", RED, background(Color.TRANSPARENT, RED));
            } else {
                GradientDrawable gradient = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR, new int[]{ACCENT, ACCENT_END});
                gradient.setCornerRadius(dp(14));
                style(connectButton, "Bağlan", ON_ACCENT, ripple(gradient));
            }
        }
        // Bildirimden kapatılırsa izin sıfırlanır; düğme de güncellenir.
        if (allowWrite.isChecked() != BridgeService.isWriteAllowed()) {
            allowWrite.setChecked(BridgeService.isWriteAllowed());
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));
        layout.setBackground(rounded(CARD, LINE));
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static TextView bold(TextView view) {
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private static Drawable ripple(GradientDrawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
    }

    private Drawable background(int fill, int stroke) {
        return ripple(rounded(fill, stroke));
    }

    private static void style(Button button, String label, int color, Drawable background) {
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setBackground(background);
    }

    private static ColorStateList switchColors(int checked, int unchecked) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{checked, unchecked});
    }

    private LinearLayout.LayoutParams spaced() {
        return spacedHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced(int heightDp) {
        return spacedHeight(dp(heightDp));
    }

    private LinearLayout.LayoutParams spacedHeight(int height) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
        params.topMargin = dp(12);
        return params;
    }

    /** Yalnızca uygulamayla gelen robot sayfasını gösterir; başka adrese gitmez. */
    @SuppressLint("SetJavaScriptEnabled")
    private WebView createRobot() {
        WebView web = new WebView(this);
        web.setBackgroundColor(BACKGROUND);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if ("skbot".equals(request.getUrl().getScheme())) {
                    showRobotFullScreen(false);
                    BridgeOverlay.reloadRobot();
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/bridge/robot.html");
        return web;
    }

    /** Ayar penceresi açıkken robot sayfası ekranın kalanını kaplar. */
    private void showRobotFullScreen(boolean full) {
        int height = full ? LinearLayout.LayoutParams.MATCH_PARENT : dp(190);
        robot.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));
        body.setVisibility(full ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onDestroy() {
        robot.destroy();
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false;
            if (Settings.canDrawOverlays(this)) {
                BridgeOverlay.setEnabled(this, true);
            }
        }
        BridgeOverlay.sync(this);
        handler.post(refresh);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
