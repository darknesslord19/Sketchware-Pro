package pro.sketchware.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;

/**
 * Bir proje klasöründe yedekli yazma, silme ve geri yükleme.
 * Her değişiklikten önce eski durum yedek klasörüne kaydedilir.
 */
final class BridgeFiles {
    private static final int MAX_LISTED_BACKUPS = 100;

    private final File root;
    private final File backups;

    BridgeFiles(File root, File backups) {
        this.root = root;
        this.backups = backups;
    }

    /** Yolu çözer ve klasörün dışına çıkılmasını engeller. */
    static File resolve(File root, String path) throws IOException {
        File base = root.getCanonicalFile();
        File target = new File(base, path).getCanonicalFile();
        if (!target.equals(base) && !target.getPath().startsWith(base.getPath() + File.separator)) {
            throw new SecurityException("Proje klasörünün dışına erişilemez");
        }
        return target;
    }

    private File resolveFile(String path) throws IOException {
        File target = resolve(root, path);
        if (target.equals(root.getCanonicalFile()) || target.isDirectory()) {
            throw new IOException("Bir dosya yolu gerekli");
        }
        return target;
    }

    /** @return alınan yedeğin kimliği */
    synchronized String write(String path, byte[] content) throws Exception {
        File target = resolveFile(path);
        String id = backup(target);
        replace(target, content);
        return id;
    }

    synchronized String delete(String path) throws Exception {
        File target = resolveFile(path);
        if (!target.isFile()) {
            throw new IOException("Dosya bulunamadı");
        }
        String id = backup(target);
        if (!target.delete()) {
            throw new IOException("Dosya silinemedi");
        }
        return id;
    }

    /** Dosyayı yedekteki durumuna döndürür; şimdiki durum da ayrıca yedeklenir. */
    synchronized String restore(String backupId) throws Exception {
        if (!backupId.matches("[0-9]+")) {
            throw new IllegalArgumentException("Geçersiz yedek kimliği");
        }
        File directory = new File(backups, backupId);
        File metaFile = new File(directory, "meta.json");
        if (!metaFile.isFile()) {
            throw new IOException("Yedek bulunamadı");
        }
        JSONObject meta = readMeta(metaFile);
        File target = resolveFile(meta.getString("path"));
        String current = backup(target);
        if (meta.getBoolean("existed")) {
            replace(target, Files.readAllBytes(new File(directory, "content").toPath()));
        } else if (target.exists() && !target.delete()) {
            throw new IOException("Dosya silinemedi");
        }
        return current;
    }

    synchronized String list() throws Exception {
        String[] names = backups.list();
        JSONArray result = new JSONArray();
        if (names != null) {
            Arrays.sort(names, Collections.reverseOrder());
            for (String name : names) {
                File metaFile = new File(new File(backups, name), "meta.json");
                if (metaFile.isFile()) {
                    result.put(readMeta(metaFile).put("backupId", name));
                }
                if (result.length() >= MAX_LISTED_BACKUPS) {
                    break;
                }
            }
        }
        return result.toString();
    }

    private static JSONObject readMeta(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private String backup(File target) throws Exception {
        long stamp = System.currentTimeMillis();
        File directory = new File(backups, String.valueOf(stamp));
        while (directory.exists()) {
            directory = new File(backups, String.valueOf(++stamp));
        }
        if (!directory.mkdirs()) {
            throw new IOException("Yedek klasörü oluşturulamadı; değişiklik yapılmadı");
        }
        boolean existed = target.isFile();
        if (existed) {
            Files.copy(target.toPath(), new File(directory, "content").toPath());
        }
        String relative = target.getPath().substring(root.getCanonicalPath().length() + 1);
        JSONObject meta = new JSONObject().put("path", relative).put("existed", existed);
        Files.write(new File(directory, "meta.json").toPath(), meta.toString().getBytes(StandardCharsets.UTF_8));
        return directory.getName();
    }

    /** Yarım dosya kalmaması için önce geçici dosyaya yazar. */
    private static void replace(File target, byte[] content) throws IOException {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Klasör oluşturulamadı");
        }
        File temp = new File(parent, target.getName() + ".claude-tmp");
        Files.write(temp.toPath(), content);
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
