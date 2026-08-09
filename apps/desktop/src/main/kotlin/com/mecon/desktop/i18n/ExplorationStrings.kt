package com.mecon.desktop.i18n

import com.mecon.desktop.i18n.en.ExplorationStrings as EnglishExplorationStrings
import com.mecon.desktop.i18n.zh.ExplorationStrings as ChineseExplorationStrings
import com.mecon.desktop.uikit.i18n.I18nRegistry
import com.mecon.desktop.uikit.i18n.Language
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.theory.RuleId

/** Translation bundle for the exploration workspace. */
internal object ExplorationStrings {
    fun install() {
        I18nRegistry.register(Language.CHINESE, ChineseExplorationStrings.values)
        I18nRegistry.register(Language.ENGLISH, EnglishExplorationStrings.values)
    }
}

internal fun explorationText(key: String): String = i18n("exploration.$key")

internal fun explorationText(key: String, vararg args: Any): String =
    explorationText(key).format(*args)

internal fun ruleLabel(ruleId: RuleId): String {
    val key = "exploration.rule.${ruleId.value}"
    val label = i18n(key)
    return if (label == key) ruleId.value.substringAfterLast('.') else label
}
