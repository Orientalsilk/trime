/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.util.mapping
import com.osfans.trime.util.pairs
import com.osfans.trime.util.string
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LegacyThemeCompatibilityTest :
    BehaviorSpec({
        Given("legacy scalar settings and toolbar actions") {
            val source = ThemeYaml.parser.parseToYamlNode(
                """
                name: legacy
                height: 48
                round_corner: 7
                style:
                  long_text_font: legacy.ttf
                  margin_x: 5
                  margin_y: 6
                  keyboard_padding_left: 2
                preset_color_schemes:
                  default: {back_color: "#000000"}
                tool_bar:
                  buttons:
                    - action: liquid_keyboard_clipboard
                """.trimIndent(),
            ).mapping!!

            val normalized = LegacyThemeCompatibility.normalize(source)
            val theme = ThemeYaml.parser.decodeFromYamlNode<Theme>(normalized)

            Then("old style keys are migrated without overriding explicitly configured modern keys") {
                theme.style.keyHeight shouldBe 48
                theme.style.roundCorner shouldBe 7f
                theme.style.keyboardPaddingLeft shouldBe 2
                theme.style.keyboardPaddingRight shouldBe 5
                theme.style.keyboardPaddingBottom shouldBe 6
                theme.style.keyboardPaddingLandBottom shouldBe 6
                normalized.pairs["style"]?.mapping?.pairs?.get("popup_font")?.string shouldBe "legacy.ttf"
            }

            Then("the retired clipboard toolbar action is migrated") {
                theme.toolBar.buttons.single().action shouldBe "clipboard_window"
            }

            Then("legacy scalar keys no longer remain as ignored top-level settings") {
                normalized.pairs.containsKey("height") shouldBe false
                normalized.pairs.containsKey("round_corner") shouldBe false
            }
        }

        Given("legacy helper mappings used for YAML anchors") {
            val source = ThemeYaml.parser.parseToYamlNode(
                """
                name: legacy
                height: {4: 50, 5: 40}
                round_corner: {1: 8}
                colors: {WHITE: 0xffffffff}
                style:
                  key_height: 41
                  round_corner: 3
                preset_color_schemes:
                  default: {back_color: "#000000"}
                """.trimIndent(),
            ).mapping!!

            val normalized = LegacyThemeCompatibility.normalize(source)
            val theme = ThemeYaml.parser.decodeFromYamlNode<Theme>(normalized)

            Then("helper mappings are removed without replacing explicit style values") {
                normalized.pairs.containsKey("height") shouldBe false
                normalized.pairs.containsKey("round_corner") shouldBe false
                normalized.pairs.containsKey("colors") shouldBe false
                theme.style.keyHeight shouldBe 41
                theme.style.roundCorner shouldBe 3f
            }
        }
    })
