package pro.sketchware.activities.main.fragments.projects_store;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Store sekmesindeki bilinen reklam ağlarını engeller ve boş kalan reklam alanlarını gizler. */
final class StoreAdBlocker {
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
            "googletagmanager.com", "google-analytics.com", "adservice.google.com", "adtrafficquality.google",
            "fundingchoicesmessages.google.com", "amazon-adsystem.com", "adnxs.com", "criteo.com",
            "criteo.net", "taboola.com", "outbrain.com", "mgid.com", "revcontent.com", "pubmatic.com",
            "rubiconproject.com", "openx.net", "media.net", "casalemedia.com", "smartadserver.com",
            "adform.net", "bidswitch.net", "sharethrough.com", "moatads.com", "adsafeprotected.com",
            "propellerads.com", "propu.sh", "monetag.com", "popads.net", "popcash.net", "adsterra.com",
            "highperformanceformat.com", "profitablegatecpm.com", "effectivegatecpm.com",
            "profitableratecpm.com", "onclickads.net", "onclickalgo.com", "onclckds.com", "exoclick.com",
            "exosrv.com", "adcash.com", "hilltopads.net", "juicyads.com", "trafficjunky.net",
            "applovin.com", "unityads.unity3d.com", "an.yandex.ru", "ad-maven.com", "galaksion.com"));

    private static final String CSS = "ins.adsbygoogle,.adsbygoogle,.google-auto-placed,.ad-banner,.ads-banner,"
            + "[id^='google_ads'],[id^='aswift'],[id^='div-gpt-ad'],[data-ad-slot],[data-ad-client],"
            + "iframe[src*='googlesyndication'],iframe[src*='doubleclick'],[id^='container-'][id*='ad']"
            + "{display:none!important;height:0!important;min-height:0!important}";

    static final String HIDE_SCRIPT = "(function(){if(document.getElementById('sk-noads'))return;"
            + "var s=document.createElement('style');s.id='sk-noads';s.textContent=\"" + CSS + "\";"
            + "(document.head||document.documentElement).appendChild(s);})()";

    private StoreAdBlocker() {
    }

    /** Adresin kendisi ya da üst alan adlarından biri listedeyse true. */
    static boolean isAdHost(String host) {
        if (host == null) {
            return false;
        }
        String part = host.toLowerCase(Locale.US);
        while (true) {
            if (HOSTS.contains(part)) {
                return true;
            }
            int dot = part.indexOf('.');
            if (dot < 0) {
                return false;
            }
            part = part.substring(dot + 1);
        }
    }
}
