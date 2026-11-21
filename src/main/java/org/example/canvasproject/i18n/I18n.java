package org.example.canvasproject.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Единственная точка получения пользовательских строк.
 * Язык: -Dslate.lang=ru|en, иначе локаль системы. Отсутствующий ключ виден как
 * !key! — это проще заметить, чем молчаливую пустую строку.
 */
public final class I18n {

    private static volatile Locale locale = resolveLocale();
    private static volatile ResourceBundle bundle = ResourceBundle.getBundle("messages", locale);

    private I18n() {}

    public static String t(String key, Object... args) {
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (java.util.MissingResourceException e) {
            return "!" + key + "!";
        }
        return args == null || args.length == 0 ? pattern : MessageFormat.format(pattern, args);
    }

    public static Locale getLocale() {
        return locale;
    }

    /** Для тестов и будущего переключателя языка в настройках. */
    public static synchronized void setLocale(Locale newLocale) {
        locale = newLocale;
        bundle = ResourceBundle.getBundle("messages", locale);
    }

    private static Locale resolveLocale() {
        String forced = System.getProperty("slate.lang");
        if (forced != null && !forced.isBlank()) return Locale.forLanguageTag(forced);
        return Locale.getDefault();
    }
}
