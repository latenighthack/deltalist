import React, { useState, useRef, useCallback, useEffect } from 'react';
import { List, AutoSizer, CellMeasurer, CellMeasurerCache } from 'react-virtualized';
import { useDeltaList, useSoftDeltaList, useFlow } from 'demo-core';

// --- Basic List Demo ---

// Observes a single ticking item's live tick count via its Kotlin StateFlow.
function TickingItemRow({ item, index, selected, onSelect }) {
    const ticks = useFlow(item.tickCount, 0);
    return (
        <li
            className={`item-card ${selected ? 'selected' : ''}`}
            onClick={() => onSelect(index)}
        >
            <span className="item-title">{item.title}</span>
            <span className="item-id">Ticks: {ticks} | StableId: {item.stableId}</span>
        </li>
    );
}

function BasicListDemo({ vm }) {
    const items = useFlow(vm.tickingItems, []);
    const [selectedIndex, setSelectedIndex] = useState(-1);

    return (
        <div className="demo-panel">
            <div className="button-bar">
                <button onClick={() => vm.addItem()}>Add</button>
                <button onClick={() => vm.batchAdd()}>Batch Add</button>
                <button onClick={() => vm.clear()}>Clear</button>
            </div>
            {selectedIndex >= 0 && selectedIndex < items.length && (
                <div className="button-bar">
                    <button onClick={() => vm.insertBefore(selectedIndex)}>Insert Before</button>
                    <button onClick={() => vm.insertAfter(selectedIndex)}>Insert After</button>
                    <button onClick={() => { vm.removeItem(selectedIndex); setSelectedIndex(-1); }}>Remove</button>
                </div>
            )}
            <ul className="item-list">
                {items.map((item, index) => (
                    <TickingItemRow
                        key={item.stableId}
                        item={item}
                        index={index}
                        selected={index === selectedIndex}
                        onSelect={(i) => setSelectedIndex(i === selectedIndex ? -1 : i)}
                    />
                ))}
            </ul>
            {items.length === 0 && <div className="empty-state">No items. Click "Add" to get started.</div>}
        </div>
    );
}

// --- Sectioned List Demo ---

function SectionedListDemo({ vm }) {
    const rows = useDeltaList(vm.rows);
    const [selectedSection, setSelectedSection] = useState(-1);

    // Count sections for index mapping
    const sectionIndices = [];
    rows.forEach((row, i) => {
        if (row && row.type === 'header') sectionIndices.push(i);
    });

    return (
        <div className="demo-panel">
            <div className="button-bar">
                <button onClick={() => vm.addSection()}>+ Section</button>
                <button onClick={() => { if (selectedSection >= 0) { vm.removeSection(selectedSection); setSelectedSection(-1); } }} disabled={selectedSection < 0}>- Section</button>
                <button onClick={() => vm.clearSections()}>Clear</button>
            </div>
            {selectedSection >= 0 && (
                <div className="button-bar">
                    <button onClick={() => vm.addItemToSection(selectedSection)}>+ Item</button>
                    <button onClick={() => vm.removeItemFromSection(selectedSection, 0)} disabled={selectedSection < 0}>- Item</button>
                    <button onClick={() => { vm.moveSectionUp(selectedSection); setSelectedSection(Math.max(0, selectedSection - 1)); }} disabled={selectedSection <= 0}>Move Up</button>
                    <button onClick={() => { vm.moveSectionDown(selectedSection); setSelectedSection(Math.min(sectionIndices.length - 1, selectedSection + 1)); }} disabled={selectedSection >= sectionIndices.length - 1}>Move Down</button>
                </div>
            )}
            <ul className="item-list">
                {rows.map((row, index) => {
                    if (!row) return null;
                    if (row.type === 'header') {
                        const sectionIdx = sectionIndices.indexOf(index);
                        return (
                            <li
                                key={`header-${index}`}
                                className={`section-header ${sectionIdx === selectedSection ? 'selected' : ''}`}
                                style={{ backgroundColor: row.color, color: '#fff' }}
                                onClick={() => setSelectedSection(sectionIdx === selectedSection ? -1 : sectionIdx)}
                            >
                                {row.title}
                            </li>
                        );
                    } else {
                        return (
                            <li key={row.id || `item-${index}`} className="section-item">
                                <span className="item-title">{row.title}</span>
                                {row.id && <span className="item-id">ID: {row.id.substring(0, 8)}...</span>}
                            </li>
                        );
                    }
                })}
            </ul>
            {rows.length === 0 && <div className="empty-state">No sections. Click "+ Section" to get started.</div>}
        </div>
    );
}

// --- Paginated List Demo ---

// A not-yet-loaded row rendered as a skeleton item (no spinner, no text). Triggers the fetch on
// mount (mirrors iOS .onAppear / Android's soft.request() in the row body). Because the list is
// virtualized, only rows scrolled into view mount, so only visible placeholders drive pagination.
function SkeletonRow({ request }) {
    useEffect(() => {
        if (request) request();
    }, [request]);
    return (
        <div className="item-card skeleton-row">
            <span className="skeleton-bar" />
        </div>
    );
}

function PaginatedListDemo({ vm }) {
    const list = useSoftDeltaList(vm.items);
    const loadingDirection = useFlow(vm.loadingDirection, null);
    const loadedCount = useFlow(vm.loadedCount, 0);
    const excludeDivisors = useFlow(vm.excludeDivisors, []);
    const listRef = useRef(null);

    // Rows self-measure their height; the cache stores each measured height so the List
    // can position rows without a hardcoded rowHeight. Width is fixed, only height varies.
    const cacheRef = useRef(null);
    if (cacheRef.current === null) {
        cacheRef.current = new CellMeasurerCache({ fixedWidth: true, defaultHeight: 52 });
    }
    const cache = cacheRef.current;

    // react-virtualized caches rendered cells; re-render the visible window whenever a
    // new delta snapshot arrives so loaded values replace their placeholders.
    useEffect(() => {
        if (listRef.current) listRef.current.forceUpdateGrid();
    }, [list]);

    const rowRenderer = useCallback(({ index, key, parent, style }) => {
        const cell = list.get(index);
        return (
            <CellMeasurer cache={cache} columnIndex={0} key={key} parent={parent} rowIndex={index}>
                {({ registerChild }) => (
                    <div ref={registerChild} style={{ ...style, paddingBottom: 6 }}>
                        {cell.loaded ? (
                            <div className="item-card">
                                <span className="item-title">#{cell.value}</span>
                                <span className="item-id">index: {index}</span>
                            </div>
                        ) : (
                            <SkeletonRow request={cell.request} />
                        )}
                    </div>
                )}
            </CellMeasurer>
        );
    }, [list, cache]);

    const divisors = [2, 3, 5, 7, 11];

    return (
        <div className="demo-panel">
            <div className="status-bar">
                <span>Paginated List (10,000 items)</span>
                {loadingDirection && <span className="loading-badge">Loading: {loadingDirection}</span>}
            </div>
            <div className="status-bar">
                <span>Loaded: {loadedCount} | Filtered: {list.loadedCount} | Total: {list.size}</span>
            </div>
            <div className="paginated-virtual-list">
                <AutoSizer>
                    {({ width, height }) => (
                        <List
                            ref={listRef}
                            width={width}
                            height={height}
                            rowCount={list.size}
                            deferredMeasurementCache={cache}
                            rowHeight={cache.rowHeight}
                            rowRenderer={rowRenderer}
                            overscanRowCount={5}
                        />
                    )}
                </AutoSizer>
            </div>
            <div className="filter-bar">
                <span>Exclude divisors of:</span>
                {divisors.map(d => (
                    <label key={d} className="filter-checkbox">
                        <input
                            type="checkbox"
                            checked={excludeDivisors && excludeDivisors.includes(d)}
                            onChange={() => vm.toggleDivisorFilter(d)}
                        />
                        {d}
                    </label>
                ))}
            </div>
        </div>
    );
}

// --- Bottom Paginated List Demo ---

// Chat-style pagination: starts scrolled to the bottom, only the bottom items load first, and
// scrolling up loads older pages. Buttons add an item at index 0 (top) and at index n (bottom).
function BottomPaginatedListDemo({ vm }) {
    const list = useSoftDeltaList(vm.messages);
    const loadingDirection = useFlow(vm.loadingDirection, null);
    const loadedCount = useFlow(vm.loadedCount, 0);
    const excludeDivisors = useFlow(vm.excludeDivisors, []);
    const listRef = useRef(null);
    const didInitialScrollRef = useRef(false);
    const pendingBottomRef = useRef(false);

    const cacheRef = useRef(null);
    if (cacheRef.current === null) {
        cacheRef.current = new CellMeasurerCache({ fixedWidth: true, defaultHeight: 52 });
    }
    const cache = cacheRef.current;

    useEffect(() => {
        if (!listRef.current) return;
        listRef.current.forceUpdateGrid();
        // Anchor at the bottom once the estimated size is known (skeleton rows show at the bottom
        // and fill in there), then again whenever an item is appended at the bottom.
        if (!didInitialScrollRef.current && list.size > 1) {
            listRef.current.scrollToRow(list.size - 1);
            didInitialScrollRef.current = true;
        } else if (pendingBottomRef.current && list.size > 0) {
            listRef.current.scrollToRow(list.size - 1);
            pendingBottomRef.current = false;
        }
    }, [list]);

    const rowRenderer = useCallback(({ index, key, parent, style }) => {
        const cell = list.get(index);
        // Manually-added items use negative values so they never collide with the paginated data.
        const isAdded = cell.loaded && cell.value < 0;
        return (
            <CellMeasurer cache={cache} columnIndex={0} key={key} parent={parent} rowIndex={index}>
                {({ registerChild }) => (
                    <div ref={registerChild} style={{ ...style, paddingBottom: 6 }}>
                        {cell.loaded ? (
                            <div className="item-card">
                                <span className={`item-title ${isAdded ? 'added-title' : ''}`}>
                                    {isAdded ? `Added #${-cell.value}` : `#${cell.value}`}
                                </span>
                                <span className="item-id">index: {index}</span>
                            </div>
                        ) : (
                            <SkeletonRow request={cell.request} />
                        )}
                    </div>
                )}
            </CellMeasurer>
        );
    }, [list, cache]);

    const divisors = [2, 3, 5, 7, 11];

    return (
        <div className="demo-panel">
            <div className="status-bar">
                <span>Bottom Paginated (scroll up for older)</span>
                {loadingDirection && <span className="loading-badge">Loading: {loadingDirection}</span>}
            </div>
            <div className="status-bar">
                <span>Loaded: {loadedCount} | Visible rows: {list.size}</span>
            </div>
            <div className="button-bar">
                <button onClick={() => vm.addAtTop()}>Add at top (0)</button>
                <button onClick={() => { vm.addAtBottom(); pendingBottomRef.current = true; }}>Add at bottom (n)</button>
            </div>
            <div className="paginated-virtual-list">
                <AutoSizer>
                    {({ width, height }) => (
                        <List
                            ref={listRef}
                            width={width}
                            height={height}
                            rowCount={list.size}
                            deferredMeasurementCache={cache}
                            rowHeight={cache.rowHeight}
                            rowRenderer={rowRenderer}
                            overscanRowCount={5}
                        />
                    )}
                </AutoSizer>
            </div>
            <div className="filter-bar">
                <span>Exclude divisors of:</span>
                {divisors.map(d => (
                    <label key={d} className="filter-checkbox">
                        <input
                            type="checkbox"
                            checked={excludeDivisors && excludeDivisors.includes(d)}
                            onChange={() => vm.toggleDivisorFilter(d)}
                        />
                        {d}
                    </label>
                ))}
            </div>
        </div>
    );
}

// --- Drag & Drop Demo ---

function DragDropDemo({ vm }) {
    const items = useDeltaList(vm.items);
    const dragState = useFlow(vm.dragState, { state: 'idle', itemTitle: null, fromIndex: -1, toIndex: -1 });
    const dragItemRef = useRef(null);

    const handleDragStart = useCallback((e, index) => {
        const success = vm.beginDrag(index);
        if (!success) {
            e.preventDefault();
            return;
        }
        dragItemRef.current = index;
        e.dataTransfer.effectAllowed = 'move';
    }, [vm]);

    const handleDragOver = useCallback((e, index) => {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        if (dragItemRef.current !== null && dragItemRef.current !== index) {
            vm.updateDragPreview(index);
        }
    }, [vm]);

    const handleDrop = useCallback((e) => {
        e.preventDefault();
        vm.commitDrag();
        dragItemRef.current = null;
    }, [vm]);

    const handleDragEnd = useCallback(() => {
        if (dragItemRef.current !== null) {
            vm.cancelDrag();
            dragItemRef.current = null;
        }
    }, [vm]);

    const state = dragState || { state: 'idle' };
    const statusText = state.state === 'idle'
        ? 'Drag items to reorder'
        : state.state === 'dragging'
            ? `Dragging "${state.itemTitle}" (${state.fromIndex} \u2192 ${state.toIndex})`
            : `Committing move of "${state.itemTitle}"...`;

    return (
        <div className="demo-panel">
            <div className={`status-bar drag-status ${state.state}`}>
                {statusText}
            </div>
            <div className="button-bar">
                <button onClick={() => vm.addItem()}>Add</button>
                <button onClick={() => vm.addPinnedItem()}>Add Pinned</button>
                <button onClick={() => vm.clear()}>Clear</button>
                <button onClick={() => vm.reset()}>Reset</button>
            </div>
            <ul className="item-list drag-list">
                {items.map((item, index) => {
                    if (!item) return null;
                    const isPinned = item.title.toLowerCase().includes('pinned');
                    return (
                        <li
                            key={item.id}
                            className={`item-card ${isPinned ? 'pinned' : 'draggable'}`}
                            draggable={!isPinned}
                            onDragStart={(e) => handleDragStart(e, index)}
                            onDragOver={(e) => handleDragOver(e, index)}
                            onDrop={handleDrop}
                            onDragEnd={handleDragEnd}
                        >
                            {!isPinned && <span className="drag-handle">&#x2630;</span>}
                            <span className="item-title">{item.title}</span>
                            <span className="item-subtitle">{isPinned ? 'Cannot be moved' : 'Drag to reorder'}</span>
                        </li>
                    );
                })}
            </ul>
            {items.length === 0 && <div className="empty-state">No items. Click "Add" to get started.</div>}
        </div>
    );
}

// --- Sorted List Demo ---

// An unordered set of profiles projected into a 4-column grid sorted alphabetically by full name.
// "Add" inserts a random unused profile (it lands in its sorted slot); tapping a profile removes it.
function SortedListDemo({ vm }) {
    const profiles = useDeltaList(vm.profiles);

    return (
        <div className="demo-panel">
            <div className="status-bar">
                <span>Unordered set, sorted by full name</span>
                <span>{profiles.length} profiles</span>
            </div>
            <div className="profile-grid">
                {profiles.map((profile) => profile ? (
                    <div
                        key={profile.id}
                        className="profile-cell"
                        onClick={() => vm.remove(profile.id)}
                        title="Tap to remove"
                    >
                        <span className="profile-first">{profile.firstName}</span>
                        <span className="profile-last">{profile.lastName}</span>
                    </div>
                ) : null)}
            </div>
            <div className="button-bar">
                <button onClick={() => vm.addRandom()}>Add</button>
            </div>
            {profiles.length === 0 && <div className="empty-state">No profiles. Click "Add" to get started.</div>}
        </div>
    );
}

// --- Main App with Tabs ---

const TABS = [
    { key: 'list', label: 'Basic List' },
    { key: 'paginated', label: 'Paginated' },
    { key: 'bottom-paginated', label: 'Bottom Paginated' },
    { key: 'sectioned', label: 'Sectioned' },
    { key: 'dragdrop', label: 'Drag & Drop' },
    { key: 'sorted', label: 'Sorted List' },
];

export default function App({ app }) {
    const [activeTab, setActiveTab] = useState('list');

    return (
        <div className="app">
            <h1>DeltaList React Demo</h1>
            <p className="subtitle">Reactive list library with efficient mutations</p>
            <nav className="tab-bar">
                {TABS.map(tab => (
                    <button
                        key={tab.key}
                        className={`tab ${activeTab === tab.key ? 'active' : ''}`}
                        onClick={() => setActiveTab(tab.key)}
                    >
                        {tab.label}
                    </button>
                ))}
            </nav>
            <div className="tab-content">
                {activeTab === 'list' && <BasicListDemo vm={app.listViewModel} />}
                {activeTab === 'paginated' && <PaginatedListDemo vm={app.paginatedListViewModel} />}
                {activeTab === 'bottom-paginated' && <BottomPaginatedListDemo vm={app.bottomPaginatedListViewModel} />}
                {activeTab === 'sectioned' && <SectionedListDemo vm={app.sectionedListViewModel} />}
                {activeTab === 'dragdrop' && <DragDropDemo vm={app.dragDropViewModel} />}
                {activeTab === 'sorted' && <SortedListDemo vm={app.sortedListViewModel} />}
            </div>
        </div>
    );
}
