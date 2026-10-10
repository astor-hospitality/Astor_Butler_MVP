package museon_online.astor_butler.i18n;

/**
 * The line that tells the language model which language to answer in. Prompts in this repository are written in
 * Russian; the line is appended to them. For the default language it is empty, so existing prompts stay unchanged.
 */
public final class ReplyLanguageInstruction {

    private ReplyLanguageInstruction() {
    }

    public static String forLocale(ResolvedLocale locale, String defaultLanguage) {
        if (locale == null || locale.is(defaultLanguage)) {
            return "";
        }
        String code = locale.language();
        return """
                Язык ответа: %s (код %s). Отвечай гостю только на этом языке, даже если контекст и примеры ниже на русском. \
                Названия блюд и напитков оставляй как в меню и при необходимости добавляй перевод в скобках; \
                имена, адреса, телефоны и ссылки не меняй."""
                .formatted(LanguageTags.englishName(code), code);
    }
}
