package com.latenighthack.deltalist.demo

import platform.Foundation.NSUUID

actual fun randomUUID(): String = NSUUID().UUIDString()
