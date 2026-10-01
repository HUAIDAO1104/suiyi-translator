package app.suiyi.translate;

import java.util.Locale;

public enum Language {
    CHINESE("中文", "Chinese (Mandarin)", "zh-CN"),
    ENGLISH("English", "English", "en-US"),
    THAI("ไทย", "Thai", "th-TH");

    public final String label;
    public final String promptName;
    public final String code;
    Language(String label, String promptName, String code) {
        this.label = label; this.promptName = promptName; this.code = code;
    }
    public Locale locale() { return Locale.forLanguageTag(code); }
    public static Language fromCode(String code) {
        for (Language language : values()) if (language.code.equals(code)) return language;
        return CHINESE;
    }
}
