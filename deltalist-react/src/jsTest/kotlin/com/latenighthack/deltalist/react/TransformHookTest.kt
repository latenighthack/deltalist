package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.mutableDeltaListOf
import kotlin.js.Promise
import kotlin.test.*

@JsModule("react") @JsNonModule
private external object TestReact {
    fun createElement(type: dynamic, props: dynamic, vararg children: dynamic): dynamic
}
@JsModule("react-dom/client") @JsNonModule
private external object TestReactClient {
    fun createRoot(container: dynamic): dynamic
}
@JsModule("react-dom") @JsNonModule
private external object TestReactDOM {
    fun flushSync(callback: () -> Unit)
}

class TransformHookTest {
    @Test fun transformUsesLatestPropsForCurrentAndFutureSnapshots(): Promise<Unit> {
        val source = mutableDeltaListOf(listOf("a"))
        val container: dynamic = js("document.createElement('div')")
        val root = TestReactClient.createRoot(container)
        var prefix = "old"
        var rendered: Any? = null
        val component: (dynamic) -> dynamic = {
            val captured = prefix
            val proxy = useMappedDeltaList(source) { raw -> "$captured-$raw" }.asDynamic()
            rendered = proxy[0]
            TestReact.createElement("div", null, rendered)
        }
        TestReactDOM.flushSync { root.render(TestReact.createElement(component, null)) }
        return Promise { resolve, reject ->
            fun fail(error: Throwable) {
                TestReactDOM.flushSync { root.unmount() }
                reject(error)
            }
            fun awaitRow(suffix: String, ready: () -> Unit) {
                var attempts = 0
                fun check() {
                    try {
                        if ((rendered as? String)?.endsWith(suffix) == true) {
                            ready()
                        } else if (attempts++ >= 200) {
                            fail(AssertionError("Timed out waiting for row $suffix; rendered $rendered"))
                        } else {
                            js("setTimeout")(::check, 10)
                        }
                    } catch (error: Throwable) { fail(error) }
                }
                check()
            }
            awaitRow("-a") {
                assertEquals("old-a", rendered)
                prefix = "new"
                TestReactDOM.flushSync { root.render(TestReact.createElement(component, null)) }
                assertEquals("new-a", rendered)
                source.set(0, "b")
                awaitRow("-b") {
                    assertEquals("new-b", rendered)
                    TestReactDOM.flushSync { root.unmount() }
                    resolve(Unit)
                }
            }
        }
    }
}
