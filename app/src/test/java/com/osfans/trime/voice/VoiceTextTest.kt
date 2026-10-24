// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VoiceTextTest :
    StringSpec({
        "split keeps the boundary punctuation with the head" {
            VoiceText.splitAfter("今天天气很好，我们出去玩。", "今天天气很好") shouldBe ("今天天气很好，" to "我们出去玩。")
        }

        "split keeps a sentence end with the head" {
            VoiceText.splitAfter("你好吗？我很好。", "你好吗") shouldBe ("你好吗？" to "我很好。")
        }

        "split returns null when the model dropped text" {
            VoiceText.splitAfter("你好。", "你好吗") shouldBe null
        }

        "commitHeld uses the model's boundary punctuation" {
            VoiceText.commitHeld("你好吗", "我很好") { "你好吗？我很好。" } shouldBe "你好吗？"
        }

        "commitHeld adds a comma when the model gives none" {
            VoiceText.commitHeld("我觉得", "这样可以") { "我觉得这样可以。" } shouldBe "我觉得，"
        }

        "commitHeld falls back to the held text alone when alignment fails" {
            VoiceText.commitHeld("你好吗", "我很好") { if (it == "你好吗") "你好吗？" else "乱码" } shouldBe "你好吗？"
        }

        "finishHeld always closes the sentence" {
            VoiceText.finishHeld("我们出去玩") { it } shouldBe "我们出去玩。"
            VoiceText.finishHeld("我们出去玩") { "我们出去玩，" } shouldBe "我们出去玩。"
            VoiceText.finishHeld("你好吗") { "你好吗？" } shouldBe "你好吗？"
            VoiceText.finishHeld("hello world") { it } shouldBe "hello world."
        }

        "glue only inserts a space between latin words" {
            VoiceText.glue("hello", "world") shouldBe "hello world"
            VoiceText.glue("你好", "world") shouldBe "你好world"
            VoiceText.glue("", "你好") shouldBe "你好"
        }
    })
