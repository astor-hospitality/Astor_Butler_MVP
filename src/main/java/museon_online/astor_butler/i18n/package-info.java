/**
 * Guest languages: which language a guest gets answered in, and where the words come from.
 *
 * <p>The FSM keeps thinking in Russian. This package sits at the edges of a conversation:
 * <ul>
 *     <li>{@link museon_online.astor_butler.i18n.GuestLocaleService} decides the reply language for one inbound
 *     message: explicit choice, then the language the guest is writing in, then the language of earlier
 *     messages, then the platform language (Telegram {@code language_code}, web {@code viewport.locale}),
 *     then Russian;</li>
 *     <li>{@link museon_online.astor_butler.i18n.GuestTexts} turns a message key into text: human-edited
 *     catalogs ({@code i18n/guest/ru.yaml}, {@code en.yaml}) first, machine translation for the long tail
 *     (cached, off by default), then a fallback language;</li>
 *     <li>{@link museon_online.astor_butler.i18n.MachineTranslator} is the port for a paid translation
 *     provider. The only implementation shipped today is a no-op, so nothing is ever sent anywhere.</li>
 * </ul>
 *
 * <p>With {@code astor.i18n.enabled=false} (the default) every guest resolves to the default language and every
 * text comes from the Russian catalog, byte for byte what the scenarios said before. The plan is in
 * {@code docs/architecture/I18N_MULTILANG_PLAN.md}.
 */
package museon_online.astor_butler.i18n;
