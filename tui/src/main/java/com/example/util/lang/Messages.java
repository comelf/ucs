package com.example.util.lang;

import java.util.Locale;
import java.util.ResourceBundle;

public class Messages {

    public enum Lang {
        EN(Locale.ENGLISH, "English"),
        KO(Locale.KOREAN, "한국어"),
        JA(Locale.JAPANESE, "日本語");

        private final Locale locale;
        private final String displayName;

        Lang(Locale locale, String displayName) {
            this.locale = locale;
            this.displayName = displayName;
        }

        public Locale getLocale() {
            return locale;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static Lang current = Lang.EN;
    private static ResourceBundle bundle = loadBundle(current);

    private static ResourceBundle loadBundle(Lang lang) {
        return ResourceBundle.getBundle("locale.messages", lang.getLocale());
    }

    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (java.util.MissingResourceException e) {
            return key;
        }
    }

    public static Lang getLang() {
        return current;
    }

    public static void setLang(Lang lang) {
        current = lang;
        bundle = loadBundle(lang);
    }
}
