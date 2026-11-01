package android.util
object Log {
    val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun d(tag: String, text: String): Int { lines.add("$tag $text"); return 0 }
}
