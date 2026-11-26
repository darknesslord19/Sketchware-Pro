# Sketchware köprüsü (salt okunur)

    Claude --MCP--> bu sunucu <--yoklama-- Sketchware uygulaması

1. Render'da bu depodan, `claude-bridge` dalını seçerek bir Blueprint oluştur (`render.yaml`).
2. Render > Environment bölümünden `TOKEN` değerini kopyala.
3. Bağlantı adresi: `https://<sunucu-adresi>/mcp/<TOKEN>`
4. Adresi Sketchware'de **Ayarlar > Claude bağlantısı** ekranına yapıştır ve Claude'a özel bağlayıcı olarak ekle.

Adresi bilen herkes proje dosyalarını okuyabilir. Paylaşma ve depoya yazma.

Araçlar: `sk_list_projects`, `sk_list_files`, `sk_read_file`, `sk_get_compile_log`.
