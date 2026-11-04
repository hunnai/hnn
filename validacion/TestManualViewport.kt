package com.example.soinubiderxl

import kotlin.math.abs

fun main() {
    fun near(a: Float, b: Float) { check(abs(a-b) < .001f) { "$a != $b" } }
    val view = ManualViewport()
    view.layout(360f,600f,1200f)
    near(view.minX,0f); near(view.minY,-600f)
    view.moveTo(-100f,-100f)
    near(view.x,0f); near(view.y,-100f)
    val focusX=(180f-view.x)/view.zoom; val focusY=(300f-view.y)/view.zoom
    view.zoomAt(2f,180f,300f)
    near((180f-view.x)/view.zoom,focusX); near((300f-view.y)/view.zoom,focusY)
    view.zoomAt(100f,180f,300f); near(view.zoom,4f)
    view.moveTo(-100000f,-100000f)
    near(view.x,-1080f); near(view.y,-4200f)
    view.moveTo(10000f,10000f); near(view.x,0f); near(view.y,0f)
    view.zoomAt(Float.NaN,180f,300f); near(view.zoom,4f)
    view.zoomAt(Float.POSITIVE_INFINITY,180f,300f); near(view.zoom,4f)
    view.zoomAt(-1f,180f,300f); near(view.zoom,1f); near(view.x,0f)
    view.layout(360f,600f,200f)
    view.moveTo(-1000f,-1000f); near(view.x,0f); near(view.y,0f)
    view.zoomAt(4f,180f,300f); near(view.minY,-200f)
    view.moveTo(-10000f,-10000f); near(view.y,-200f)
    view.layout(0f,0f,0f); near(view.x,0f); near(view.y,0f)
    println("PASS manual viewport: pinch anchor, zoom limits, scroll bounds, resize and short documents")
}
