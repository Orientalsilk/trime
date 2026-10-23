/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LegacyKeyNameCompatibilityTest :
    BehaviorSpec({
        beforeTest {
            KeyCode.androidKeyNameToCode = { name ->
                if (name == "KEYCODE_HELP") KeyEvent.KEYCODE_HELP else 0
            }
        }
        afterSpec {
            KeyCode.androidKeyNameToCode = KeyCode.defaultAndroidKeyNameToCode
        }

        Given("an old theme using title-case Android key names") {
            Then("Help resolves through the canonical Android key name") {
                KeyCode.nameToKeyCode("Help") shouldBe KeyEvent.KEYCODE_HELP
            }
        }
    })
