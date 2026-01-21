package com.latenighthack.deltalist

import kotlinx.coroutines.flow.Flow

/**
 * A flow of sectioned delta emissions.
 * Preserves section structure for UI frameworks that support native sections
 * (UICollectionView, UITableView).
 */
typealias SectionedDeltaList<S, T> = Flow<SectionedDelta<S, T>>

@Deprecated("Use SectionedDeltaList instead", ReplaceWith("SectionedDeltaList<S, T>"))
typealias SectionedDeltaFlow<S, T> = SectionedDeltaList<S, T>
