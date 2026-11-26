package pro.sketchware.bridge;

import java.net.URI;

/** "https://host/mcp/ANAHTAR" biçimindeki bağlantı adresi. */
final class BridgeLink {
    final String base;
    final String token;

    private BridgeLink(String base, String token) {
        this.base = base;
        this.token = token;
    }

    /** @return adres geçersizse null. Yalnızca https kabul edilir. */
    static BridgeLink parse(String link) {
        try {
            URI uri = URI.create(link.trim());
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || path == null
                    || !path.matches("/mcp/[^/]+/?")) {
                return null;
            }
            String token = path.substring("/mcp/".length()).replace("/", "");
            return new BridgeLink("https://" + uri.getRawAuthority(), token);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    String endpoint(String name) {
        return base + "/device/" + token + "/" + name;
    }
}
