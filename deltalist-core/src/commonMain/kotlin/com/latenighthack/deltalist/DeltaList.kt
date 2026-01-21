package com.latenighthack.deltalist

import kotlinx.coroutines.flow.Flow

typealias DeltaList<T> = Flow<Delta<T>>

@Deprecated("Use DeltaList instead", ReplaceWith("DeltaList<T>"))
typealias DeltaFlow<T> = DeltaList<T>
