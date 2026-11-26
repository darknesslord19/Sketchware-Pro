package pro.sketchware.bridge;

import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import a.a.a.lC;
import a.a.a.oB;

/** Claude'dan gelen komutlar. Bu sürümdeki komutların tümü salt okunurdur. */
final class BridgeCommands {
    private static final int CHUNK = 60_000;
    private static final long MAX_FILE_SIZE = 8L * 1024 * 1024;
    private static final int MAX_ENTRIES = 500;
    /** Sketchware'in şifreli sakladığı proje verisi dosyaları. */
    private static final List<String> ENCRYPTED = Arrays.asList("view", "logic", "file", "library", "resource");

    private BridgeCommands() {
    }

    static String run(String action, JSONObject args) throws Exception {
        switch (action) {
            case "list_projects":
                return listProjects();
            case "list_files":
                return listFiles(args);
            case "read_file":
                return readFile(args);
            case "compile_log":
                return compileLog(args);
            default:
                throw new IllegalArgumentException("Bilinmeyen komut: " + action);
        }
    }

    private static String listProjects() throws JSONException {
        JSONArray result = new JSONArray();
        String[] keys = {"sc_id", "my_app_name", "my_ws_name", "my_sc_pkg_name", "sc_ver_name", "sc_ver_code"};
        for (HashMap<String, Object> project : lC.a()) {
            JSONObject item = new JSONObject();
            for (String key : keys) {
                item.put(key, String.valueOf(project.get(key)));
            }
            result.put(item);
        }
        return result.toString();
    }

    private static String projectId(JSONObject args) {
        String id = args.optString("projectId");
        if (!id.matches("[0-9]{1,10}")) {
            throw new IllegalArgumentException("Geçersiz proje numarası");
        }
        return id;
    }

    private static File root(JSONObject args) {
        String id = projectId(args);
        String relative;
        switch (args.optString("area")) {
            case "data":
                relative = ".sketchware/data/" + id;
                break;
            case "generated":
                relative = ".sketchware/mysc/" + id + "/app/src/main";
                break;
            default:
                throw new IllegalArgumentException("area 'data' veya 'generated' olmalı");
        }
        return new File(Environment.getExternalStorageDirectory(), relative);
    }

    /** Yolu çözer ve proje klasörünün dışına çıkılmasını engeller. */
    private static File resolve(File root, String path) throws IOException {
        File base = root.getCanonicalFile();
        File target = new File(base, path).getCanonicalFile();
        if (!target.equals(base) && !target.getPath().startsWith(base.getPath() + File.separator)) {
            throw new SecurityException("Proje klasörünün dışına erişilemez");
        }
        return target;
    }

    private static String listFiles(JSONObject args) throws Exception {
        File directory = resolve(root(args), args.optString("path", ""));
        File[] files = directory.listFiles();
        if (files == null) {
            throw new IOException("Klasör bulunamadı ya da okunamıyor");
        }
        Arrays.sort(files);
        JSONArray entries = new JSONArray();
        for (int i = 0; i < Math.min(files.length, MAX_ENTRIES); i++) {
            entries.put(new JSONObject()
                    .put("name", files[i].getName())
                    .put("directory", files[i].isDirectory())
                    .put("size", files[i].isFile() ? files[i].length() : 0));
        }
        return new JSONObject().put("total", files.length).put("entries", entries).toString();
    }

    private static String readFile(JSONObject args) throws Exception {
        File root = root(args).getCanonicalFile();
        File file = resolve(root, args.optString("path"));
        byte[] bytes = readBytes(file);
        if ("data".equals(args.optString("area")) && root.equals(file.getParentFile())
                && ENCRYPTED.contains(file.getName())) {
            bytes = new oB().b(bytes);
        } else {
            for (int i = 0; i < Math.min(bytes.length, 4096); i++) {
                if (bytes[i] == 0) {
                    throw new IOException("İkili dosya, metin olarak okunamaz");
                }
            }
        }
        return slice(new String(bytes, StandardCharsets.UTF_8), args.optInt("offset", 0));
    }

    private static String compileLog(JSONObject args) throws Exception {
        File log = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + projectId(args) + "/compile_log");
        if (!log.isFile()) {
            return "Kaydedilmiş derleme hatası yok.";
        }
        return slice(new String(readBytes(log), StandardCharsets.UTF_8), args.optInt("offset", 0));
    }

    private static byte[] readBytes(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("Dosya bulunamadı");
        }
        if (file.length() > MAX_FILE_SIZE) {
            throw new IOException("Dosya çok büyük");
        }
        return Files.readAllBytes(file.toPath());
    }

    private static String slice(String text, int offset) {
        int start = Math.max(0, Math.min(offset, text.length()));
        int end = Math.min(text.length(), start + CHUNK);
        String part = text.substring(start, end);
        if (end < text.length()) {
            part += "\n\n[Devamı var. offset=" + end + ", toplam=" + text.length() + "]";
        }
        return part;
    }
}
