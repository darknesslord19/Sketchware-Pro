package pro.sketchware.activities.main.fragments.projects_store;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.transition.MaterialFadeThrough;

import pro.sketchware.R;
import pro.sketchware.utility.SketchwareUtil;

import java.io.ByteArrayInputStream;

/**
 * Store sekmesi: Sketchub'ın herkese açık web sitesini gösterir. API anahtarı gerekmez.
 * Uygulama içinde yalnızca sketchub.in adresleri açılır. Bilinen reklam ağları engellenir,
 * dosyalar uygulama içinde indirilir.
 */
public class ProjectsStoreFragment extends Fragment {
    private static final String HOME = "https://web.sketchub.in/";
    /** Dış bağlantı ve indirmeler yalnızca kullanıcının dokunuşundan sonraki bu süre içinde işlenir. */
    private static final long TAP_WINDOW_MS = 60_000;

    private long lastTap;

    private WebView web;
    private ProgressBar progress;
    private View errorView;
    private boolean failed;
    private ViewTreeObserver.OnGlobalLayoutListener bottomSpace;

    private final OnBackPressedCallback webBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (web != null) {
                web.goBack();
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setEnterTransition(new MaterialFadeThrough());
        setReturnTransition(new MaterialFadeThrough());
        setExitTransition(new MaterialFadeThrough());
        setReenterTransition(new MaterialFadeThrough());
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(requireContext());

        web = new WebView(requireContext());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new StoreClient());
        web.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                lastTap = SystemClock.elapsedRealtime();
            }
            return false;
        });
        web.setDownloadListener((url, userAgent, disposition, mimeType, length) -> download(url));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(requireContext());
        root.addView(progress, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        errorView = createErrorView();
        root.addView(errorView, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), webBack);

        // Alt gezinme çubuğu içeriğin üstüne bindiği için, örtülen yükseklik kadar boşluk bırakılır.
        bottomSpace = () -> {
            View navigation = requireActivity().findViewById(R.id.bottom_nav);
            if (navigation == null || view.getHeight() == 0) {
                return;
            }
            int[] own = new int[2];
            int[] bar = new int[2];
            view.getLocationInWindow(own);
            navigation.getLocationInWindow(bar);
            int covered = Math.max(0, own[1] + view.getHeight() - bar[1]);
            if (view.getPaddingBottom() != covered) {
                view.setPadding(0, 0, 0, covered);
            }
        };
        view.getViewTreeObserver().addOnGlobalLayoutListener(bottomSpace);

        web.loadUrl(HOME);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        updateBack();
    }

    @Override
    public void onDestroyView() {
        requireView().getViewTreeObserver().removeOnGlobalLayoutListener(bottomSpace);
        webBack.setEnabled(false);
        ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        web = null;
        super.onDestroyView();
    }

    /** Geri tuşu yalnızca bu sekme görünürken sitede geri gider. */
    private void updateBack() {
        webBack.setEnabled(!isHidden() && web != null && web.canGoBack());
    }

    private View createErrorView() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int space = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(space, space, space, space);

        TextView message = new TextView(requireContext());
        message.setText("Sketchub yüklenemedi. İnternet bağlantını kontrol et.");
        message.setGravity(Gravity.CENTER);
        layout.addView(message);

        MaterialButton retry = new MaterialButton(requireContext());
        retry.setText("Tekrar dene");
        retry.setOnClickListener(v -> {
            failed = false;
            errorView.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            String current = web.getUrl();
            web.loadUrl(current != null && isSketchub(Uri.parse(current)) ? current : HOME);
        });
        layout.addView(retry, new LinearLayout.LayoutParams(-2, -2));
        layout.setVisibility(View.GONE);
        return layout;
    }

    private static boolean isSketchub(Uri uri) {
        String host = uri.getHost();
        return "https".equals(uri.getScheme()) && host != null
                && (host.equals("sketchub.in") || host.endsWith(".sketchub.in"));
    }

    private boolean recentlyTapped() {
        return lastTap != 0 && SystemClock.elapsedRealtime() - lastTap < TAP_WINDOW_MS;
    }

    /** Her dokunuş en fazla bir indirme ya da dış bağlantı başlatır. */
    private void download(String url) {
        if (web == null || !recentlyTapped() || StoreAdBlocker.isAdHost(Uri.parse(url).getHost())) {
            return;
        }
        lastTap = 0;
        progress.setVisibility(View.GONE);
        StoreDownloader.start(requireActivity(), url, web.getSettings().getUserAgentString(), web.getUrl());
    }

    private void openOutside(Uri uri) {
        // Yalnızca e-posta adresleri doğrudan dışarıda açılır.
        if (!"mailto".equals(uri.getScheme())) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (RuntimeException e) {
            SketchwareUtil.toast("Bağlantı açılamadı");
        }
    }

    private final class StoreClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (isSketchub(request.getUrl())) {
                return false;
            }
            if (request.isForMainFrame()) {
                Uri uri = request.getUrl();
                if ("mailto".equals(uri.getScheme())) {
                    if (recentlyTapped()) {
                        openOutside(uri);
                    }
                } else {
                    // Dosyaysa uygulama içinde iner; web sayfasıysa tarayıcı için onay istenir.
                    download(uri.toString());
                }
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (StoreAdBlocker.isAdHost(request.getUrl().getHost())) {
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            view.evaluateJavascript(StoreAdBlocker.HIDE_SCRIPT, null);
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(StoreAdBlocker.HIDE_SCRIPT, null);
            progress.setVisibility(View.GONE);
            updateBack();
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            updateBack();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && !failed) {
                failed = true;
                progress.setVisibility(View.GONE);
                view.setVisibility(View.INVISIBLE);
                errorView.setVisibility(View.VISIBLE);
            }
        }
    }
}
