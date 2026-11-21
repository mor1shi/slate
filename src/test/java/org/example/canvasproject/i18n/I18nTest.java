package org.example.canvasproject.i18n;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTest {

    @AfterAll
    static void restoreDefault() {
        I18n.setLocale(Locale.getDefault());
    }

    @Test
    void russianAndEnglishBundlesHaveTheSameKeys() {
        Set<String> ru = new TreeSet<>(ResourceBundle
                .getBundle("messages", Locale.forLanguageTag("ru")).keySet());
        Set<String> en = new TreeSet<>(ResourceBundle
                .getBundle("messages", Locale.ENGLISH).keySet());
        assertEquals(en, ru);
        assertFalse(ru.isEmpty());
    }

    @Test
    void languageSwitchesWithoutRestart() {
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("Cancel", I18n.t("button.cancel"));

        I18n.setLocale(Locale.forLanguageTag("ru"));
        assertEquals("Отмена", I18n.t("button.cancel"));
    }

    @Test
    void argumentsAreFormattedAndMissingKeyIsVisible() {
        I18n.setLocale(Locale.forLanguageTag("ru"));
        assertTrue(I18n.t("dialog.delete.header", "Проект").contains("«Проект»"));
        assertEquals("!no.such.key!", I18n.t("no.such.key"));
    }
}
