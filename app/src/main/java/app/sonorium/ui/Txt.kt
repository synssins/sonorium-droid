package app.sonorium.ui

import android.content.Context

/**
 * The app's texts from res/values/strings.xml, anywhere (screens, dialogs, toasts): the application's
 * resources, set when the app starts.
 */
object Txt {
    @Volatile private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /** The text [id], with [args] in its %1$s, %2$s, ... */
    fun get(id: Int, vararg args: Any?): String {
        val c = context ?: error("Txt used before the app started")
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}
