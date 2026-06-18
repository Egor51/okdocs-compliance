package io.okdocs.compliance.rules.ru;

import java.util.List;
import java.util.Locale;

/**
 * Справочник доменов иностранных облачных/CDN/хостинг-провайдеров для DNS-правил RU-юрисдикции.
 * CNAME на такой домен — сигнал, что веб-инфраструктура или хранение могут быть вне РФ (риск
 * локализации, ч. 5 ст. 18 152-ФЗ). Список заведомо неполный и эвристический: правило даёт находку
 * с пометкой о необходимости ручной проверки фактического размещения данных.
 */
final class ForeignCloudDomains {

    /** Суффиксы доменов известных иностранных cloud/CDN/PaaS-провайдеров. */
    static final List<String> SUFFIXES = List.of(
            "amazonaws.com", "cloudfront.net", "awsdns",            // AWS
            "azure.com", "azureedge.net", "azurewebsites.net", "windows.net", // Azure
            "googleusercontent.com", "googleapis.com", "googlehosted.com", "appspot.com", // GCP
            "cloudflare.net", "cloudflare.com", "cdn.cloudflare.net", // Cloudflare
            "fastly.net", "fastlylb.net",                          // Fastly
            "akamai.net", "akamaiedge.net", "akamaized.net", "edgekey.net", // Akamai
            "vercel.app", "vercel-dns.com",                        // Vercel
            "netlify.app", "netlify.com",                          // Netlify
            "herokuapp.com", "herokudns.com",                      // Heroku
            "github.io", "githubusercontent.com",                  // GitHub Pages
            "wpengine.com", "wixdns.net", "squarespace.com",       // прочие PaaS/конструкторы
            "digitaloceanspaces.com", "ondigitalocean.app");       // DigitalOcean

    private ForeignCloudDomains() {
    }

    /** Указывает ли DNS-имя (CNAME/NS) на известный иностранный cloud/CDN-провайдер. */
    static String matchedProvider(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        for (String suffix : SUFFIXES) {
            if (n.equals(suffix) || n.endsWith("." + suffix) || n.contains(suffix)) {
                return suffix;
            }
        }
        return null;
    }
}
