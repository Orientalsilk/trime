/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.util.mapping
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LegacyLiquidKeyboardCompatibilityTest :
    BehaviorSpec({
        Given("a liquid keyboard configuration containing removed legacy types") {
            val theme = ThemeYaml.parser.decodeFromYamlNode<Theme>(
                ThemeYaml.parser.parseToYamlNode(
                    """
                    name: legacy
                    style: {}
                    preset_color_schemes:
                      default: {back_color: "#000000"}
                    liquid_keyboard:
                      keyboards: [draft, clipboard, collection, emoji]
                      draft: {type: DRAFT, name: 草稿}
                      clipboard: {type: CLIPBOARD, name: 剪贴}
                      collection: {type: COLLECTION, name: 收藏}
                      emoji: {type: SINGLE, name: Emoji, keys: "🙂😂"}
                    """.trimIndent(),
                ).mapping!!,
            )

            Then("removed database-backed tabs are skipped while valid keyboards remain") {
                theme.liquidKeyboard.keyboards.map { it.id } shouldBe listOf("emoji")
                theme.liquidKeyboard.keyboards.single().keys.map { it.text } shouldBe listOf("🙂", "😂")
            }
        }
    })
