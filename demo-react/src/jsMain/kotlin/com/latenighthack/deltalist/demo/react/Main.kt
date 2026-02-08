package com.latenighthack.deltalist.demo.react

import react.create
import react.dom.client.createRoot
import web.dom.document
import web.dom.ElementId

fun main() {
    val root = document.getElementById(ElementId("root")) ?: error("Root element not found")
    createRoot(root).render(App.create())
}
