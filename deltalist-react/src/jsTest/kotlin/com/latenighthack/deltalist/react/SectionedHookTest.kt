package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.mutableSectionedDeltaListOf
import kotlin.js.Promise
import kotlin.test.*

@JsModule("react") @JsNonModule
private external object SectionedTestReact {
    fun createElement(type: dynamic, props: dynamic, vararg children: dynamic): dynamic
}
@JsModule("react-dom/client") @JsNonModule
private external object SectionedTestReactClient {
    fun createRoot(container: dynamic): dynamic
}
@JsModule("react-dom") @JsNonModule
private external object SectionedTestReactDOM {
    fun flushSync(callback: () -> Unit)
}

class SectionedHookTest {
    @Test fun rendersSectionsAndRerendersOnMutationsAndHeaderProps(): Promise<Unit> {
        val source = mutableSectionedDeltaListOf<Any?, Any?>(listOf(Section("Popular", listOf("a"))))
        val container: dynamic = js("document.createElement('div')")
        val root = SectionedTestReactClient.createRoot(container)
        var prefix = "old"
        var rendered = ""
        val component: (dynamic) -> dynamic = {
            val captured = prefix
            val handle = useMappedSectionedDeltaList(source, { "$captured-$it" }, { it }).asDynamic()
            val sections: Array<dynamic> = handle.sections
            rendered = sections.joinToString("|") { section ->
                "${section.header}:" + js("Array.from")(section.items).join(",")
            }
            SectionedTestReact.createElement("div", null, rendered)
        }
        SectionedTestReactDOM.flushSync { root.render(SectionedTestReact.createElement(component, null)) }
        return Promise { resolve, reject ->
            fun fail(error: Throwable) {
                SectionedTestReactDOM.flushSync { root.unmount() }
                reject(error)
            }
            fun await(expected: String, ready: () -> Unit) {
                var attempts = 0
                fun check() {
                    try {
                        if (rendered == expected) ready()
                        else if (attempts++ >= 200) fail(AssertionError("Timed out waiting for $expected; rendered $rendered"))
                        else js("setTimeout")(::check, 10)
                    } catch (error: Throwable) { fail(error) }
                }
                check()
            }
            await("old-Popular:a") {
                source.appendSection("More", listOf("b"))
                await("old-Popular:a|old-More:b") {
                    prefix = "new"
                    SectionedTestReactDOM.flushSync { root.render(SectionedTestReact.createElement(component, null)) }
                    assertEquals("new-Popular:a|new-More:b", rendered)
                    SectionedTestReactDOM.flushSync { root.unmount() }
                    resolve(Unit)
                }
            }
        }
    }
}
