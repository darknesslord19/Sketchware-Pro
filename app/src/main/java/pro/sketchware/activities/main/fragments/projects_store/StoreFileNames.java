package pro.sketchware.activities.main.fragments.projects_store;

import java.io.File;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** İndirilen dosyaya güvenli bir ad verir. Android'e bağlı değildir. */
final class StoreFileNames {
    private static final Pattern EXTENDED = Pattern.compile("filename\\*\\s*=\\s*[\\w-]*''([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN = Pattern.compile("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))", Pattern.CASE_INSENSITIVE);

    private StoreFileNames() {
    }

    /** Önce sunucunun verdiği ad, yoksa adresin son parçası kullanılır. */
    static String choose(String disposition, String path) {
        String name = null;
        if (disposition != null) {
            Matcher matcher = EXTENDED.matcher(disposition);
            if (matcher.find()) {
                name = decode(matcher.group(1).trim());
            } else {
                matcher = PLAIN.matcher(disposition);
                if (matcher.find()) {
                    name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2).trim();
                }
            }
        }
        if ((name == null || name.isEmpty()) && path != null) {
            name = decode(path.substring(path.lastIndexOf('/') + 1));
        }
        return clean(name);
    }

    /** Klasör ayıraçlarını ve sorunlu karakterleri kaldırır; ad klasör dışına çıkamaz. */
    static String clean(String name) {
        if (name == null) {
            name = "";
        }
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.length() > 100) {
            name = name.substring(name.length() - 100);
        }
        return name.isEmpty() ? "indirilen_dosya" : name;
    }

    /** Aynı adda dosya varsa üzerine yazmaz, sonuna sayı ekler. */
    static File unused(File folder, String name) {
        File file = new File(folder, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; file.exists(); i++) {
            file = new File(folder, base + " (" + i + ")" + extension);
        }
        return file;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
