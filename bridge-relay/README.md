# Sketchware köprüsü 

    Claude --MCP--> bu sunucu <--yoklama-- Sketchware uygulaması

1. Render'da bu depodan, `claude-bridge` dalını seçerek bir Blueprint oluştur (`render.yaml`).
2. Render > Environment bölümünde `TOKEN` değerini **en az 24 karakterlik, yalnızca harf ve rakamdan** oluşan rastgele bir değerle değiştir. Otomatik üretilen değerdeki `/` bağlantı adresini bozar.
3. Bağlantı adresi: `https://<sunucu-adresi>/mcp/<TOKEN>`
4. Adresi Sketchware'de **Ayarlar > Claude bağlantısı** ekranına yapıştır ve Claude'a özel bağlayıcı olarak ekle.

Adresi bilen herkes proje dosyalarını okuyabilir; yazma izni açıksa değiştirebilir. Paylaşma ve depoya yazma.

Araçlar: `sk_list_projects`, `sk_list_files`, `sk_read_file`, `sk_get_compile_log`, `sk_write_file`, `sk_delete_file`, `sk_list_backups`, `sk_restore_backup`, `sk_create_project`.
