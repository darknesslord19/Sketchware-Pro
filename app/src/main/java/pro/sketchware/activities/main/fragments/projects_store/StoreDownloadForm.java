package pro.sketchware.activities.main.fragments.projects_store;

/**
 * Sketchub'ın indirme düğmesi dosyayı form gönderimiyle (reCAPTCHA koduyla) ister. WebView bu veriyi
 * indirme dinleyicisine vermediği için gönderim yakalanıp uygulamaya aktarılır. Doğrulamayı kullanıcı
 * kendisi yapar. Sayfaya Java nesnesi açılmaz; veri özel bir adresle taşınır.
 */
final class StoreDownloadForm {
    static final String SCHEME = "skstore-download";

    static final String SCRIPT = "(function(){if(window.skDownloadHook)return;window.skDownloadHook=true;"
            + "document.addEventListener('submit',function(e){var f=e.target;"
            + "if(!f||String(f.method).toLowerCase()!=='post'"
            + "||!f.querySelector('button[name=\\'action\\'][value=\\'download\\']'))return;"
            + "var d=new URLSearchParams(new FormData(f));"
            // Doğrulama yapılmadıysa site kendi uyarısını göstersin.
            + "if(f.querySelector('[name=g-recaptcha-response]')&&!d.get('g-recaptcha-response'))return;"
            + "d.set('action','download');e.preventDefault();"
            + "location.href='" + SCHEME + ":?'+d.toString();},true);})()";

    private StoreDownloadForm() {
    }

    /** Özel adresteki form verisini olduğu gibi (kodlanmış hâliyle) döndürür. */
    static String body(String request) {
        return request.substring(request.indexOf('?') + 1);
    }
}
