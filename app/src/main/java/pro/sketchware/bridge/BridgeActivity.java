package pro.sketchware.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.besome.sketch.lib.base.BaseAppCompatActivity;

import org.json.JSONObject;

import pro.sketchware.utility.SketchwareUtil;

/** Köprü adresinin girildiği ve bağlantının açılıp kapatıldığı ekran. */
public class BridgeActivity extends BaseAppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private WebView robot;
    private ScrollView scroll;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            status.setText("Durum: " + BridgeService.getStatus());
            robot.evaluateJavascript("window.skBot&&skBot.update("
                    + JSONObject.quote(BridgeService.getRobotState()) + ","
                    + JSONObject.quote(BridgeService.getTask()) + ")", null);
            handler.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Claude bağlantısı");

        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView info = new TextView(this);
        info.setText("Claude'a bağlayıcı olarak eklediğin Sketchware köprü adresini yapıştır.\n\n"
                + "Bağlantı açıkken Claude, projelerinin dosyalarını ve derleme hatalarını okuyabilir. "
                + "Dosyalardaki API anahtarları da buna dahildir.\n\n"
                + "\"Yazmaya izin ver\" açıkken proje oluşturabilir, dosya yazıp silebilir. Her değişiklikten "
                + "önce .sketchware/claude_backups klasörüne yedek alınır. Yazılan kod, derleyip kurduğun "
                + "uygulamada çalışır. Düzenlenen proje kapalı olmalı.\n\n"
                + "Adresi kimseyle paylaşma.");

        EditText input = new EditText(this);
        input.setHint("https://sunucun.onrender.com/mcp/ANAHTAR");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(BridgeService.getSavedLink(this));

        status = new TextView(this);

        CheckBox allowWrite = new CheckBox(this);
        allowWrite.setText("Yazmaya izin ver");
        allowWrite.setChecked(BridgeService.isWriteAllowed());
        allowWrite.setOnCheckedChangeListener((button, checked) -> BridgeService.setWriteAllowed(checked));

        Button connect = new Button(this);
        connect.setText("Bağlan");
        connect.setOnClickListener(v -> {
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
        });

        Button disconnect = new Button(this);
        disconnect.setText("Bağlantıyı kapat");
        disconnect.setOnClickListener(v -> {
            allowWrite.setChecked(false);
            stopService(new Intent(this, BridgeService.class));
        });

        Button robotSettings = new Button(this);
        robotSettings.setText("Robot ayarları");
        robotSettings.setOnClickListener(v -> {
            showRobotFullScreen(true);
            robot.evaluateJavascript("window.skBot&&skBot.edit()", null);
        });

        content.addView(info);
        content.addView(input);
        content.addView(status);
        content.addView(allowWrite);
        content.addView(connect);
        content.addView(disconnect);
        content.addView(robotSettings);

        scroll = new ScrollView(this);
        scroll.addView(content);

        robot = createRobot();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.addView(robot);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        showRobotFullScreen(false);
        setContentView(root);
    }

    /** Yalnızca uygulamayla gelen robot sayfasını gösterir; başka adrese gitmez. */
    @SuppressLint("SetJavaScriptEnabled")
    private WebView createRobot() {
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if ("skbot".equals(request.getUrl().getScheme())) {
                    showRobotFullScreen(false);
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/bridge/robot.html");
        return web;
    }

    /** Ayar penceresi açıkken robot sayfası tüm ekranı kaplar. */
    private void showRobotFullScreen(boolean full) {
        int height = full ? LinearLayout.LayoutParams.MATCH_PARENT
                : Math.round(210 * getResources().getDisplayMetrics().density);
        robot.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));
        scroll.setVisibility(full ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onDestroy() {
        robot.destroy();
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
