/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.osfans.trime.util.mapping
import com.osfans.trime.util.pairs
import com.osfans.trime.util.scalar
import com.osfans.trime.util.string
import com.osfans.trime.util.yamlListOf
import com.osfans.trime.util.yamlMapOf
import com.osfans.trime.util.yamlScalarOf

/**
 * Normalizes the small set of legacy theme options that have a direct modern
 * equivalent. This runs only on the in-memory tree; deployed/source YAML files
 * are never rewritten.
 *
 * Old themes also use top-level height, round_corner, and colors mappings as
 * YAML anchor tables. Those are authoring helpers, not runtime settings, so
 * they are removed after YAML aliases have already been resolved. Scalar
 * height/round_corner values, in contrast, are migrated to style.
 */
internal object LegacyThemeCompatibility {
    private val actionFields = setOf("action", "long_press_action")

    fun normalize(source: YamlMap): YamlMap {
        val root = source.pairs.toMutableMap()
        val originalStyle = root["style"]
        val style = originalStyle?.mapping?.pairs?.toMutableMap() ?: linkedMapOf()
        var rewriteStyle = originalStyle is YamlMap

        fun migrateRootScalar(sourceKey: String, targetKey: String) {
            val value = root[sourceKey] ?: return
            if (value.scalar != null) {
                if (targetKey !in style) {
                    style[targetKey] = value
                    rewriteStyle = true
                }
                root.remove(sourceKey)
            } else if (value.mapping != null) {
                // Legacy themes use these mappings to declare YAML anchors.
                root.remove(sourceKey)
            }
        }

        migrateRootScalar("height", "key_height")
        migrateRootScalar("round_corner", "round_corner")

        // These are common anchor tables in older Trime themes, not runtime
        // configuration sections. Any aliases have already been resolved.
        if (root["colors"]?.mapping != null) root.remove("colors")

        // Settings with a clear one-to-one mapping to the current renderer.
        // long_text_font is a supported legacy field on GeneralStyle, so it is
        // deliberately retained rather than being confused with popup_font.
        val marginX = style.remove("margin_x")
        if (marginX != null) {
            if ("keyboard_padding_left" !in style) style["keyboard_padding_left"] = marginX
            if ("keyboard_padding_right" !in style) style["keyboard_padding_right"] = marginX
            rewriteStyle = true
        }
        val marginY = style.remove("margin_y")
        if (marginY != null) {
            if ("keyboard_padding_bottom" !in style) style["keyboard_padding_bottom"] = marginY
            if ("keyboard_padding_land_bottom" !in style) style["keyboard_padding_land_bottom"] = marginY
            rewriteStyle = true
        }

        if (rewriteStyle) root["style"] = yamlMapOf(style)

        // The pre-2025 liquid keyboard used a toolbar action with this name;
        // clipboard is now a separate window and has a dedicated action.
        root["tool_bar"]?.let { root["tool_bar"] = normalizeToolbarActions(it) }
        return yamlMapOf(root)
    }

    private fun normalizeToolbarActions(node: YamlNode): YamlNode = when (node) {
        is YamlMap -> yamlMapOf(
            node.pairs.map { (key, value) ->
                key to if (key in actionFields && value.string == "liquid_keyboard_clipboard") {
                    yamlScalarOf("clipboard_window")
                } else {
                    normalizeToolbarActions(value)
                }
            },
        )

        is YamlList -> yamlListOf(*node.items.map(::normalizeToolbarActions).toTypedArray())

        else -> node
    }
}
