# DeltaList — Readiness Review

*Impartial technical review · 2026-06-18 · against commit `8f9bae2`, version `0.1.0`*

> "There are only two hard problems in mobile: cache invalidation and responsive lists."

---

## 1. What this is

DeltaList is a Kotlin Multiplatform library for **reactive, incrementally-updated lists**. The
central idea is a single type:

```kotlin
typealias DeltaList<T> = Flow<Delta<T>>

data class Delta<T>(val items: SoftList<T>, val change: Change)
```

Every emission carries a full **snapshot** (`items`) *and* a description of **what changed since
the last emission** (`change`: either `Reload` or an ordered list of `Insert / Remove / Update /
Move` mutations in running coordinates). UI bindings consume the change to drive minimal,
animatable updates (`RecyclerView.notifyItemRange*`, Compose keys, `UICollectionView` batch
updates, React reconciliation) instead of diffing or rebinding the whole list.

Two ideas elevate it above a plain `Flow<List<T>>`:

- **`SoftList`** — a snapshot honest about *load state*. A slot is either `Present(value)` or
  `NotLoaded` (a placeholder a paginated source hasn't fetched). It deliberately does **not**
  implement `List`, so you can't accidentally iterate and trigger fetches.
- **`LazyList` + `StableItem`** — per-item lifecycle (`acquire`/`release`) so expensive transforms
  are computed only for on-screen items, plus session-stable IDs that follow an item through moves.

On top of the core sit composable **operators** (`map`, `filter`, `concat`, `flatten`, `groupBy`,
`diff`, `stableIds`, lazy `map`) and mutable sources (`MutableDeltaList`,
`MutableSectionedDeltaList`, `MoveableDeltaList` for drag-and-drop, `PaginatedDeltaList`).

**Maturity context:** 52 commits, single author, ~5 months of focused work (Jan–Feb 2026),
version `0.1.0`. Kotlin 2.1.21 / AGP 8.7.3 / coroutines 1.9.0 — all current.

---

## 2. Usefulness assessment

### In an Android app — **High**

This is the library's strongest pitch. The `RecyclerView` "Inconsistency detected" crash and the
boilerplate of hand-writing `DiffUtil` callbacks are real, recurring Android pain points, and the
`deltalist-android-recyclerview` binding addresses them directly: it maps core mutations to
`notifyItemRange*`, auto-detects stable IDs from `StableItem`, manages `LazyList` acquire/release
across view attach/detach, **and** simulates the running-mutation stream to fall back to a full
reload on any desync — defusing the exact crash that makes raw RecyclerView updates dangerous.

The Compose binding (`collectAsDeltaState` + `LazyColumn(key = stableId)`) follows the idiomatic
key-diffing model correctly, including a genuinely subtle case (releasing the right lazy slot when
an item moves while composed) that it gets right.

The `deltalist-android-notifications` module is an unexpected standout: a full notification-tray
DSL that reconciles mutations to system notifications by stable ID, respects Android's post-rate
cap, and routes actions through durable `PendingIntent`s. Niche, but well-engineered.

**Verdict:** For an Android team already on coroutines/Flow with churny lists (chat, feeds,
search-as-you-type, drag-to-reorder), the value is concrete today — *if you can vendor it as source*
(see §4).

### In a Kotlin Multiplatform app — **Moderate, asymmetric**

The architecture is the right shape for KMP: list logic and view-models live in `commonMain`
(see `demo-core`, where every screen's view-model is platform-agnostic), and each platform supplies
a thin binding. The `SoftList`/`Delta` helpers are deliberately designed to avoid Kotlin/Native↔Swift
bridging cost (`loadedItems()`, `getItemAt()` take the `Delta` directly).

But the platform story is uneven:

- **Android:** production-grade bindings (above).
- **iOS:** *works in the demo* (UIKit + SwiftUI, drag-drop, pagination, sections) via SKIE, but the
  iOS glue lives inside `demo-ios` / `demo-core/swift` — **there is no shippable iOS binding
  module**. An adopter must lift demo code.
- **JS/React:** `useDeltaList` works via a clever `SoftList`→JS-array proxy, but it **replaces the
  whole list each emission** (no incremental mapping) and **never releases `LazyList` items** (a
  leak in any real React app). Early-alpha.

**Verdict:** Compelling as a *pattern* for KMP, but only Android is turnkey. iOS needs packaging;
React needs real work.

---

## 3. Criticism (the hard part)

### 3.1 Confirmed correctness bug: `Concat` / `SectionedConcat` mutation offset

`Concat.kt` and `SectionedConcat.kt` offset a downstream source's mutations by the **post-mutation**
size of the preceding source(s) (`Concat.kt:39`, `SectionedConcat.kt:29`), then concatenate the two
independently-offset mutation lists rather than interleaving them into one running-coordinate
sequence. Mutations apply *sequentially in running coordinates* (`Apply.kt`), so when **more than
one source mutates in the same `combine` tick** and the first source's change alters its size, the
offset for the second source is wrong. Result: misplaced/aliased updates. This is only safe when at
most one side mutates per emission — an invariant nothing enforces. **This should block a 1.0.**

### 3.2 Inspection-resistant heuristic arithmetic

`Filter.kt` (~32 KB) and `PaginatedDeltaList.emitChange` carry the highest *latent* risk. The filter's
`adjustMutationsForPlaceholders` and `estimatedFilteredSize` ratio-extrapolation, and pagination's
leading/trailing placeholder reconciliation matrix, are heuristic, hard to verify by reading, and
will emit churny insert/remove deltas at the boundary. They *pass current tests* but are the places a
subtle production bug will hide. They need a property-based oracle (see §5), not more example tests.

### 3.3 Coverage gaps that matter

- **The entire sectioned-list subsystem is untested** — `MutableSectionedDeltaList`,
  `SectionedConcat`, `GroupBy`, `Section`, `SectionedDelta` have *zero* dedicated tests, yet sections
  are a headline feature with their own demo screen.
- **Binding tests barely exist.** Only `notifications` has a unit test (`TrayControllerTest`), and
  **CI doesn't even run it** (no `testDebugUnitTest` job). RecyclerView, Compose, and React have none
  — so the most crash-prone integration code (index translation → framework APIs) is unverified by CI.

### 3.4 Not consumable as an artifact

There is **no publishing infrastructure at all** — no `maven-publish`, `signing`, Dokka, npm, or
SPM/XCFramework publish wiring in any module. The root build comments that this is "intentionally
deferred." Today the only way to use DeltaList is `project(...)` source inclusion. For a library
whose whole value is being a dependency, this is the single biggest gap between "impressive repo" and
"usable product."

### 3.5 Smaller edges

- Compose lazy-release helpers are **opt-in and easy to forget**; a dev reading `delta.items`
  directly never releases lazy items (leak), yet the docs say to "rely on composition lifecycle"
  which doesn't actually release.
- `GroupBy` and the sectioned-concat paths fall back to `Reload` aggressively (any multi-section
  change), trading the library's core benefit (incrementality) for safety.
- `Diff` Phase 2 is O(n²) (`indexOf`/`removeAt`/`add`) on large reorders.
- React binding has no LazyList lifecycle and no incremental path.
- Single-author bus-factor of 1; no `CONTRIBUTING`, no published API docs, one-line README.

---

## 4. Feedback / support (what's genuinely good)

- **The core engine is production-grade where it's tested.** `DiffOracleTest` applies emitted
  mutations back onto the prior snapshot and asserts equality against a brute-force recompute, across
  **2,000 seeded fuzz trials** plus curated cases, *and* asserts perf invariants (prepend to a 10k
  list emits exactly one `Insert`, zero `Move`s). This is the gold-standard testing approach and it's
  green on JVM, JS/Node, and iOS simulator.
- **`SoftList` is a genuinely good abstraction.** Refusing to implement `List` so unloaded slots
  can't be silently iterated/fetched is a principled, correct design choice that prevents a whole bug
  class.
- **The lock-free operators are handled deliberately** — `LazyMap` and dynamic `Filter` use
  atomics + epoch/generation guards so superseded snapshots' side effects become safe no-ops. This is
  hard concurrency code written with care.
- **Defensive bindings.** The RecyclerView desync-detection-then-reload fallback shows someone who
  has actually been burned by `RecyclerView` in production and engineered around it.
- **Documentation in code is excellent.** KDoc explains *contracts and intent*, not just mechanics.
- **Clean adopter ergonomics.** View-models read naturally (`SectionedListViewModel` is ~90 readable
  lines); the on-ramp constructors (`List` → `SoftList`) keep simple cases simple.
- **Modern, healthy toolchain + CI** that exercises three platforms on every push.

---

## 5. Recommendations before 1.0

1. **Fix the concat offset bug (§3.1).** Interleave into one running-coordinate sequence, or restrict
   to single-source-mutation-per-tick and enforce it.
2. **Build a generic property-based oracle** that, for *every* operator, applies the translated
   mutations via `Apply.kt` and asserts equality against a recompute-from-scratch — then run it over
   Filter, Flatten, Concat, GroupBy, SectionedConcat, and Paginated. This converts §3.2's latent risk
   into caught regressions.
3. **Test the sectioned subsystem and add binding tests**; wire `testDebugUnitTest` into CI so the one
   existing binding test actually runs.
4. **Ship artifacts:** `maven-publish` + signing + Dokka for the JVM/Android modules; package the iOS
   binding as a real SPM/XCFramework module (lift it out of the demo); decide whether React is
   supported or experimental and label it.
5. **Close the Compose lazy-release footgun** — either make release automatic via the standard access
   path or loudly document that the helper is mandatory.
6. Add a real README (the problem, a 20-line quickstart, a feature/platform support matrix) and a
   `CONTRIBUTING` to address bus-factor.

---

## 6. Rating

| Dimension | Rating | Notes |
|---|---|---|
| Core design & abstractions | ★★★★★ | `SoftList`/`Delta`/lazy lifecycle is principled and coherent |
| Core correctness (tested paths) | ★★★★☆ | Oracle + fuzzing is excellent; concat bug + untested sections dock it |
| Operator layer | ★★★☆☆ | Diff/StableIds/Map/LazyMap solid; Concat bug, Filter/Paginated heuristics risky |
| Android bindings | ★★★★☆ | RecyclerView + notifications production-grade; Compose lazy footgun |
| iOS bindings | ★★★☆☆ | Works in demo via SKIE, but not packaged as a module |
| React/JS binding | ★★☆☆☆ | Functional but no lazy lifecycle, no incremental path |
| Test coverage (overall) | ★★★☆☆ | Core strong; sections + bindings barely covered; CI gaps |
| Packaging / consumability | ★☆☆☆☆ | No publishing infra — source inclusion only |
| Docs & project health | ★★★☆☆ | Superb in-code KDoc; thin README, bus-factor 1 |

### Overall: **★★★½ / 5 — Promising, not yet production-shippable**

DeltaList is an **architecturally excellent, well-tested core** wrapped in **uneven platform
support and zero packaging**. The central abstractions are the kind you'd want to copy; the Android
path is nearly turnkey; the test discipline on the engine is exemplary.

It is held back from a confident recommendation by three things, in priority order: **(1)** a
confirmed `concat` correctness bug, **(2)** the complete absence of publishable artifacts, and
**(3)** untested high-risk areas (sectioned lists, bindings, filter/pagination heuristics).

- **Adopt now if:** you're an Android team comfortable vendoring it as source, with churny lists,
  and you avoid `concat`/multi-source-mutation paths until §3.1 is fixed.
- **Wait if:** you need a Maven/SPM/npm dependency, a turnkey iOS or React binding, or you lean
  heavily on sections and pagination — give it one more hardening cycle.

The gap from here to a 4.5★ "ship it" is **execution, not invention**: fix one bug, add an oracle
across operators, test sections + bindings, and publish artifacts. The hard design thinking is
already done and done well.
