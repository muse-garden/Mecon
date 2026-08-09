package com.mecon.desktop.i18n

import com.mecon.desktop.uikit.i18n.I18nRegistry
import com.mecon.desktop.uikit.i18n.Language
import com.mecon.theory.harmony.ChordCatalogChapterDiscovery
import com.mecon.theory.harmony.ChordCatalogChapterProvider
import kotlin.test.Test
import kotlin.test.assertNotEquals

class ChordCatalogI18nTest {
    @Test
    fun everyDiscoveredCategoryHasChineseAndEnglishText() {
        ExplorationStrings.install()
        val original = I18nRegistry.getCurrentLanguage()
        try {
            val categories = ChordCatalogChapterDiscovery.discover()
                .flatMap(ChordCatalogChapterProvider::chordCatalogContributions)
                .map { it.category }
            listOf(Language.CHINESE, Language.ENGLISH).forEach { language ->
                I18nRegistry.setLanguage(language)
                categories.forEach { category ->
                    assertNotEquals(category.titleKey, I18nRegistry.get(category.titleKey))
                    assertNotEquals(category.descriptionKey, I18nRegistry.get(category.descriptionKey))
                }
            }
        } finally {
            I18nRegistry.setLanguage(original)
        }
    }
}
