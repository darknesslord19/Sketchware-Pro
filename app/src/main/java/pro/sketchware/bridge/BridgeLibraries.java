package pro.sketchware.bridge;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import org.cosmic.ide.dependency.resolver.api.Artifact;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import dev.aldi.sayuti.editor.manage.LocalLibrariesUtil;
import mod.hey.studios.build.BuildSettings;
import mod.hey.studios.util.Helper;
import mod.jbk.build.BuiltInLibraries;
import mod.pranav.dependency.resolver.DependencyResolver;

/** Sketchware'in kendi indiricisiyle yerel kütüphane indirir ve projelere bağlar. */
final class BridgeLibraries {
    private static final String PART = "[A-Za-z0-9_][A-Za-z0-9_.\\-]*";
    private static final int MAX_LOG = 40;

    // İndirme uzun sürdüğü için arka planda çalışır; durumu ayrıca sorulur. Aynı anda tek indirme.
    private static final List<String> log = new ArrayList<>();
    private static final List<String> problems = new ArrayList<>();
    private static final List<String> downloaded = new ArrayList<>();
    private static String state = "idle";
    private static String coordinate = "";

    private BridgeLibraries() {
    }

    private static File directory() {
        return BridgeCommands.storage(".sketchware/libs/local_libs");
    }

    static String list() throws JSONException {
        File[] files = directory().listFiles(File::isDirectory);
        JSONArray result = new JSONArray();
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File file : files) {
                result.put(new JSONObject()
                        .put("name", file.getName())
                        .put("ready", new File(file, "classes.dex").isFile()));
            }
        }
        return result.toString();
    }

    static synchronized String download(String dependency, boolean skipDependencies) {
        String[] parts = dependency.trim().split(":");
        if (parts.length != 3 || !parts[0].matches(PART) || !parts[1].matches(PART) || !parts[2].matches(PART)) {
            throw new IllegalArgumentException("Biçim grup:ad:sürüm olmalı, ör. com.squareup.okhttp3:okhttp:4.12.0");
        }
        if ("running".equals(state)) {
            throw new IllegalStateException("Başka bir indirme sürüyor: " + coordinate);
        }
        log.clear();
        problems.clear();
        downloaded.clear();
        coordinate = dependency.trim();
        state = "running";
        new Thread(() -> run(parts, skipDependencies), "claude-library").start();
        return "İndirme başladı: " + coordinate + ". Durumu sk_download_status ile sor.";
    }

    private static synchronized void note(String line) {
        log.add(line);
        if (log.size() > MAX_LOG) {
            log.remove(0);
        }
    }

    private static synchronized void problem(String line) {
        problems.add(line);
        note(line);
    }

    private static synchronized void complete(List<String> names) {
        downloaded.addAll(names);
    }

    private static synchronized void finish(String root) {
        // Alt bağımlılık listesi ana kütüphaneyi içermeyebilir; yalnızca dex'i hazır olanlar bildirilir.
        if (!downloaded.isEmpty() || problems.isEmpty()) {
            if (!downloaded.contains(root)) {
                downloaded.add(0, root);
            }
        }
        downloaded.removeIf(library -> !new File(directory(), library + "/classes.dex").isFile());
        state = downloaded.isEmpty() ? "failed" : problems.isEmpty() ? "done" : "done_with_problems";
    }

    private static String name(Artifact artifact) {
        return artifact.getArtifactId() + "-v" + artifact.getVersion();
    }

    private static void run(String[] parts, boolean skipDependencies) {
        try {
            BuiltInLibraries.maybeExtractAndroidJar((message, progress) -> {
            });
            BuiltInLibraries.maybeExtractCoreLambdaStubsJar();
            DependencyResolver resolver = new DependencyResolver(
                    parts[0], parts[1], parts[2], skipDependencies, new BuildSettings("system"));
            resolver.resolveDependency(new DependencyResolver.DependencyResolverCallback() {
                @Override
                public void onDownloadStart(@NonNull Artifact artifact) {
                    note("İndiriliyor: " + name(artifact));
                }

                @Override
                public void onResolutionComplete(@NonNull Artifact artifact) {
                    note("Hazır: " + name(artifact));
                }

                @Override
                public void onArtifactNotFound(@NonNull Artifact artifact) {
                    problem("Bulunamadı: " + name(artifact));
                }

                @Override
                public void onVersionNotFound(@NonNull Artifact artifact) {
                    problem("Sürüm bulunamadı: " + name(artifact));
                }

                @Override
                public void onDependenciesNotFound(@NonNull Artifact artifact) {
                    problem("Bağımlılık bulunamadı: " + name(artifact));
                }

                @Override
                public void onDownloadError(@NonNull Artifact artifact, @NonNull Throwable error) {
                    problem("İndirme hatası: " + name(artifact) + ": " + error.getMessage());
                }

                @Override
                public void dexingFailed(@NonNull Artifact artifact, @NonNull Exception error) {
                    problem("Dex hatası: " + name(artifact) + ": " + error.getMessage());
                }

                @Override
                public void invalidPackaging(@NonNull Artifact artifact) {
                    problem("Desteklenmeyen paket türü: " + name(artifact));
                }

                @Override
                public void onResolutionError(@NonNull Exception error) {
                    problem("Çözümleme hatası: " + error.getMessage());
                }

                @Override
                public void onTaskCompleted(@NonNull List<String> names) {
                    complete(names);
                }
            });
        } catch (Throwable e) {
            problem("Hata: " + e);
        } finally {
            finish(parts[1] + "-v" + parts[2]);
        }
    }

    static synchronized String status() throws JSONException {
        return new JSONObject()
                .put("state", state)
                .put("dependency", coordinate)
                .put("libraries", new JSONArray(downloaded))
                .put("problems", new JSONArray(problems))
                .put("log", new JSONArray(log))
                .toString();
    }

    /** İndirilmiş kütüphaneleri projenin local_library listesine ekler. */
    static String addToProject(BridgeFiles files, File projectData, JSONArray names) throws Exception {
        File listFile = new File(projectData, "local_library");
        ArrayList<HashMap<String, Object>> used = null;
        if (listFile.isFile()) {
            String text = new String(Files.readAllBytes(listFile.toPath()), StandardCharsets.UTF_8);
            used = new Gson().fromJson(text, Helper.TYPE_MAP_LIST);
        }
        if (used == null) {
            used = new ArrayList<>();
        }
        for (int i = 0; i < names.length(); i++) {
            String library = names.getString(i);
            if (!library.matches(PART) || !new File(directory(), library + "/classes.dex").isFile()) {
                throw new IOException("İndirilmiş ve hazır bir kütüphane değil: " + library);
            }
            used.removeIf(entry -> library.equals(String.valueOf(entry.get("name"))));
            used.add(LocalLibrariesUtil.createLibraryMap(library, null));
        }
        return files.write("local_library", new Gson().toJson(used).getBytes(StandardCharsets.UTF_8));
    }
}
