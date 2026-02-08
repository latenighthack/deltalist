package com.latenighthack.deltalist.demo.react

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1

val App = FC<Props> {
    div {
        h1 { +"DeltaList React Demo" }
    }
}
