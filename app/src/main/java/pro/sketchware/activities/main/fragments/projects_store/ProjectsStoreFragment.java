package pro.sketchware.activities.main.fragments.projects_store;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.transition.MaterialFadeThrough;

import pro.sketchware.BuildConfig;
import pro.sketchware.activities.main.fragments.projects_store.adapters.StorePagerProjectsAdapter;
import pro.sketchware.activities.main.fragments.projects_store.adapters.StoreProjectsAdapter;
import pro.sketchware.activities.main.fragments.projects_store.api.ProjectModel;
import pro.sketchware.activities.main.fragments.projects_store.api.SketchubAPI;
import pro.sketchware.activities.main.fragments.projects_store.classes.CenterZoomListener;
import pro.sketchware.databinding.FragmentProjectsStoreBinding;
import pro.sketchware.utility.SketchwareUtil;
import pro.sketchware.utility.UI;

public class ProjectsStoreFragment extends Fragment {
    private static final String PREFS = "sketchub_store";
    private static final String KEY_API = "api_key";
    /** Sketchub belgelerinde (docs.sketchub.in) verilen anahtar alma adresi. */
    private static final String KEY_PAGE = "https://sketchub.in/temp/register_api.php";

    private FragmentProjectsStoreBinding binding;
    private SketchubAPI sketchubAPI;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setEnterTransition(new MaterialFadeThrough());
        setReturnTransition(new MaterialFadeThrough());
        setExitTransition(new MaterialFadeThrough());
        setReenterTransition(new MaterialFadeThrough());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentProjectsStoreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupRecyclerView(binding.editorsChoiceProjectsRecyclerView);
        binding.storeSideNote.setMaxLines(8);
        binding.cardWarning.setOnClickListener(v -> showKeyDialog());
        loadStore();

        UI.addSystemWindowInsetToPadding(binding.textEditorsChoice, true, false, true, false);
        UI.addSystemWindowInsetToPadding(binding.editorsChoiceProjectsRecyclerView, true, false, true, false);
        UI.addSystemWindowInsetToPadding(binding.textRecent, true, false, true, false);
        UI.addSystemWindowInsetToPadding(binding.recentProjectsRecyclerView, true, false, true, false);
        UI.addSystemWindowInsetToPadding(binding.textMostDownloaded, true, false, true, false);
        UI.addSystemWindowInsetToPadding(binding.mostDownloadedProjectsRecyclerView, true, false, true, true);
        UI.addSystemWindowInsetToMargin(binding.cardWarning, true, false, true, false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // avoid memory leaks
    }

    private void setupRecyclerView(RecyclerView recyclerView) {
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false);
        recyclerView.setLayoutManager(layoutManager);

        SnapHelper snapHelper = new LinearSnapHelper();
        snapHelper.attachToRecyclerView(recyclerView);

        recyclerView.addOnScrollListener(new CenterZoomListener());

        recyclerView.setClipToPadding(false);
        recyclerView.setClipChildren(false);

        ViewParent parent = recyclerView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).setClipChildren(false);
            ((ViewGroup) parent).setClipToPadding(false);
        }
    }

    /** Kullanıcının girdiği anahtar; yoksa derlemeye eklenmiş olan. */
    private String apiKey() {
        String saved = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "");
        if (!saved.isEmpty()) {
            return saved;
        }
        // Derlemede ortam değişkeni yoksa alan "null" yazısı olur.
        String built = BuildConfig.SKETCHUB_API_KEY;
        return "null".equals(built) ? "" : built;
    }

    private void loadStore() {
        String key = apiKey();
        if (key.isEmpty()) {
            binding.storeSideNote.setText("Mağaza için Sketchub API anahtarı gerekli. Anahtar eklemek için dokun.");
            return;
        }
        binding.storeSideNote.setText("Yükleniyor… Anahtarı değiştirmek için dokun.");
        sketchubAPI = new SketchubAPI(key);
        fetchData();
    }

    private void fetchData() {
        var activity = getActivity();
        SketchubAPI requested = sketchubAPI;
        sketchubAPI.getEditorsChoicerProjects(1, projectModel -> {
            if (accept(requested, projectModel)) {
                binding.editorsChoiceProjectsRecyclerView.setAdapter(new StorePagerProjectsAdapter(projectModel.getProjects(), activity));
            }
        });
        sketchubAPI.getMostDownloadedProjects(1, projectModel -> {
            if (accept(requested, projectModel)) {
                binding.mostDownloadedProjectsRecyclerView.setAdapter(new StoreProjectsAdapter(projectModel.getProjects(), activity));
            }
        });
        sketchubAPI.getRecentProjects(1, projectModel -> {
            if (accept(requested, projectModel)) {
                binding.recentProjectsRecyclerView.setAdapter(new StoreProjectsAdapter(projectModel.getProjects(), activity));
            }
        });
    }

    /** Yanıt kullanılabilir mi bakar; değilse nedeni kartta gösterir. */
    private boolean accept(SketchubAPI requested, ProjectModel model) {
        // Ekran kapanmış ya da anahtar bu arada değişmiş olabilir.
        if (binding == null || requested != sketchubAPI) {
            return false;
        }
        if (model == null || model.getProjects() == null) {
            binding.storeSideNote.setText("Mağaza yüklenemedi. İnternetini ve anahtarını kontrol et "
                    + "(saatlik sorgu sınırı da dolmuş olabilir). Anahtar için dokun.");
            return false;
        }
        binding.storeSideNote.setText("Mağaza Sketchub'dan yükleniyor. Anahtarı değiştirmek için dokun.");
        return true;
    }

    private void showKeyDialog() {
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setHint("Sketchub API anahtarı");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, ""));
        FrameLayout box = new FrameLayout(context);
        int side = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(side, side / 2, side, 0);
        box.addView(input);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Sketchub API anahtarı")
                .setMessage("Anahtar yalnızca bu telefonda saklanır. Sketchub hesabın yoksa önce hesap açman gerekir.")
                .setView(box)
                .setPositiveButton("Kaydet", (dialog, which) -> {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString(KEY_API, input.getText().toString().trim()).apply();
                    if (binding != null) {
                        loadStore();
                    }
                })
                .setNeutralButton("Anahtar al", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(KEY_PAGE)));
                    } catch (RuntimeException e) {
                        SketchwareUtil.toast("Tarayıcı açılamadı");
                    }
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }
}
