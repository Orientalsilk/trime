// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.sms

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SmsCodeTest :
    StringSpec({
        "code after the keyword" {
            SmsCode.extract("【某某】您的验证码是 123456，5分钟内有效。") shouldBe "123456"
        }

        "code glued to the keyword" {
            SmsCode.extract("【银行】验证码384920，请勿泄露") shouldBe "384920"
        }

        "code before the keyword" {
            SmsCode.extract("8421是您的登录验证码") shouldBe "8421"
        }

        "english message" {
            SmsCode.extract("Your verification code is 552901. Do not share it.") shouldBe "552901"
        }

        "no keyword means no code" {
            SmsCode.extract("您尾号1234的账户支出100元") shouldBe null
        }

        "phone numbers are not codes" {
            SmsCode.extract("验证码已发送至13800138000") shouldBe null
        }

        "amounts are not codes" {
            SmsCode.extract("验证码 123456，本次交易金额 5000 元") shouldBe "123456"
        }
    })
