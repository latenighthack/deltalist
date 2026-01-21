package com.latenighthack.deltalist.demo

actual fun randomUUID(): String = java.util.UUID.randomUUID().toString()
