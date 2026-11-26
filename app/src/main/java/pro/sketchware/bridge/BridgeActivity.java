package pro.sketchware.bridge;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.besome.sketch.lib.base.BaseAppCompatActivity;

import pro.sketchware.utility.SketchwareUtil;

/** Köprü adresinin girildiği ve bağlantının açılıp kapatıldığı ekran. */
public class BridgeActivity extends BaseAppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            status.setText("Durum: " + BridgeService.getStatus());
            handler.postDelayed(this, 1000);
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
                + "Dosyalardaki API anahtarları da buna dahildir. Bu sürüm hiçbir dosyayı değiştirmez.\n\n"
                + "Adresi kimseyle paylaşma.");

        EditText input = new EditText(this);
        input.setHint("https://sunucun.onrender.com/mcp/ANAHTAR");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(BridgeService.getSavedLink(this));

        status = new TextView(this);

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
        disconnect.setOnClickListener(v -> stopService(new Intent(this, BridgeService.class)));

        content.addView(info);
        content.addView(input);
        content.addView(status);
        content.addView(connect);
        content.addView(disconnect);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(content);
        setContentView(scroll);
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
