package com.latenighthack.deltalist.demo

import kotlin.random.Random

actual fun randomUUID(): String {
    val hexChars = "0123456789abcdef"
    fun randomHex(length: Int) = buildString {
        repeat(length) { append(hexChars[Random.nextInt(16)]) }
    }
    return "${randomHex(8)}-${randomHex(4)}-4${randomHex(3)}-${randomHex(4)}-${randomHex(12)}"
}
