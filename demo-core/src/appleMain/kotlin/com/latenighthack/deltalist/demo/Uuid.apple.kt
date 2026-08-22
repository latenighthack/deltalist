package com.latenighthack.deltalist.demo

import platform.Foundation.NSUUID

// Shared by every Apple target (iOS + macOS); NSUUID is available on all of them.
actual fun randomUUID(): String = NSUUID().UUIDString()
