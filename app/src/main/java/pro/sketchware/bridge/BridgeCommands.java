package pro.sketchware.bridge;

import android.content.Context;
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

import a.a.a.GB;
import a.a.a.lC;
import a.a.a.nB;
import a.a.a.oB;
import a.a.a.wq;
import mod.hey.studios.project.ProjectSettings;
import mod.hey.studios.util.ProjectFile;

/** Claude'dan gelen komutlar. Değişiklik yapanlar yalnızca yazma izni açıkken çalışır. */
final class BridgeCommands {
    private static final int CHUNK = 60_000;
    private static final long MAX_FILE_SIZE = 8L * 1024 * 1024;
    private static final int MAX_ENTRIES = 500;
    /** Sketchware'in şifreli sakladığı proje verisi dosyaları. */
    private static final List<String> ENCRYPTED = Arrays.asList("view", "logic", "file", "library", "resource");

    private static final List<String> WRITES =
            Arrays.asList("write_file", "delete_file", "restore_backup", "create_project");
    private static final int MAX_WRITE = 400_000;

    private BridgeCommands() {
    }

    static String run(Context context, String action, JSONObject args, boolean writeAllowed) throws Exception {
        if (WRITES.contains(action) && !writeAllowed) {
            throw new SecurityException("Yazma izni kapalı. Kullanıcı Sketchware'de Ayarlar > Claude bağlantısı "
                    + "ekranından \"Yazmaya izin ver\" seçeneğini açmalı.");
        }
        switch (action) {
            case "write_file":
                return writeFile(args);
            case "delete_file":
                return changed("Silindi", project(args).delete(args.getString("path")));
            case "restore_backup":
                return changed("Geri yüklendi", project(args).restore(args.getString("backupId")));
            case "list_backups":
                return project(args).list();
            case "create_project":
                return createProject(context, args);
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

    private static String listFiles(JSONObject args) throws Exception {
        File directory = BridgeFiles.resolve(root(args), args.optString("path", ""));
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
        File file = BridgeFiles.resolve(root, args.optString("path"));
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

    private static File storage(String relative) {
        return new File(Environment.getExternalStorageDirectory(), relative);
    }

    /** Var olan bir projenin veri klasörü ve yedekleri. */
    private static BridgeFiles project(JSONObject args) throws IOException {
        String id = projectId(args);
        if (!storage(".sketchware/mysc/list/" + id + "/project").isFile()) {
            throw new IOException("Bu numarada proje yok");
        }
        return new BridgeFiles(storage(".sketchware/data/" + id), storage(".sketchware/claude_backups/" + id));
    }

    private static String changed(String what, String backupId) {
        return what + ". Yedek kimliği: " + backupId;
    }

    private static String writeFile(JSONObject args) throws Exception {
        BridgeFiles files = project(args);
        String id = projectId(args);
        String content = args.getString("content");
        if (content.length() > MAX_WRITE) {
            throw new IOException("İçerik çok büyük");
        }
        File root = storage(".sketchware/data/" + id).getCanonicalFile();
        File target = BridgeFiles.resolve(root, args.getString("path"));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (root.equals(target.getParentFile()) && ENCRYPTED.contains(target.getName())) {
            if (storage(".sketchware/bak/" + id + "/" + target.getName()).exists()) {
                throw new IOException("Projenin kaydedilmemiş bir kopyası var. Kullanıcı projeyi açıp "
                        + "kaydetmeli ve kapatmalı.");
            }
            bytes = new oB().d(content);
        }
        return changed("Yazıldı", files.write(args.getString("path"), bytes));
    }

    private static String createProject(Context context, JSONObject args) throws Exception {
        String appName = args.getString("appName").trim();
        String packageName = args.getString("packageName").trim();
        String workspace = args.getString("workspaceName").trim();
        if (appName.isEmpty() || appName.length() > 50 || appName.matches(".*[<>&\"'\\\\].*")) {
            throw new IllegalArgumentException("Uygulama adı 1-50 karakter olmalı ve < > & \" ' \\ içermemeli");
        }
        if (!packageName.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
            throw new IllegalArgumentException("Paket adı com.ornek.uygulama biçiminde, küçük harfli olmalı");
        }
        if (!workspace.matches("[A-Za-z][A-Za-z0-9_]{0,39}")) {
            throw new IllegalArgumentException("Proje adı harfle başlamalı; harf, rakam ve _ içerebilir");
        }

        int number = Integer.parseInt(lC.b());
        // Eski bir projeden kalan klasörlerin üzerine yazılmaz.
        while (storage(".sketchware/mysc/list/" + number).exists()
                || storage(".sketchware/data/" + number).exists()
                || storage(".sketchware/mysc/" + number).exists()
                || storage(".sketchware/bak/" + number).exists()) {
            number++;
        }
        String id = String.valueOf(number);

        HashMap<String, Object> data = new HashMap<>();
        data.put("sc_id", id);
        data.put("my_sc_pkg_name", packageName);
        data.put("my_ws_name", workspace);
        data.put("my_app_name", appName);
        data.put("my_sc_reg_dt", new nB().a("yyyyMMddHHmmss"));
        data.put("custom_icon", false);
        data.put("isIconAdaptive", false);
        data.put("sc_ver_code", "1");
        data.put("sc_ver_name", "1.0");
        data.put("sketchware_ver", GB.d(context));
        String[][] colors = {
                {"color_accent", ProjectFile.COLOR_ACCENT},
                {"color_primary", ProjectFile.COLOR_PRIMARY},
                {"color_primary_dark", ProjectFile.COLOR_PRIMARY_DARK},
                {"color_control_highlight", ProjectFile.COLOR_CONTROL_HIGHLIGHT},
                {"color_control_normal", ProjectFile.COLOR_CONTROL_NORMAL}};
        for (String[] color : colors) {
            data.put(color[0], ProjectFile.getDefaultColor(color[1]));
        }
        lC.a(id, data);
        if (!storage(".sketchware/mysc/list/" + id + "/project").isFile()) {
            throw new IOException("Proje kaydedilemedi");
        }
        wq.a(context, id);
        ProjectSettings settings = new ProjectSettings(id);
        settings.setValue(ProjectSettings.SETTING_NEW_XML_COMMAND, ProjectSettings.SETTING_GENERIC_VALUE_TRUE);
        settings.setValue(ProjectSettings.SETTING_ENABLE_VIEWBINDING, ProjectSettings.SETTING_GENERIC_VALUE_FALSE);
        return "Proje oluşturuldu. projectId=" + id;
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
