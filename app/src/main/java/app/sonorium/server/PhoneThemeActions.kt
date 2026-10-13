package app.sonorium.server

import android.content.Context
import app.sonorium.engine.PhonePlayer
import app.sonorium.engine.ThemePrep
import app.sonorium.local.ImportRules
import app.sonorium.local.LocalTheme
import app.sonorium.local.PackageRules

/**
 * What the themes-on-this-phone part of [ServerViewModel] does to the phone player and its prepared
 * copies (here so the view model reaches them in its own package).
 */
internal object PhoneThemeActions {
    /** The themes on the phone were read again: the chosen theme follows its folder. */
    fun synced(context: Context, themes: List<LocalTheme>) = PhonePlayer.sync(context, themes)

    /** The themes folder changed: stop, forget the chosen theme, delete every prepared copy. */
    suspend fun folderChanged(context: Context) {
        PhonePlayer.reset(context)
        ThemePrep.clearAll(context)
    }

    /** Before removing [theme]: stop it if it's the one chosen. */
    fun removing(context: Context, theme: LocalTheme) = PhonePlayer.forget(context, theme.docId)

    /** After removing [theme]: delete its prepared copy and decoded audio. */
    suspend fun removed(context: Context, theme: LocalTheme) = ThemePrep.clear(context, theme.folder)

    fun importedText(plan: ImportRules.Plan): String = ImportRules.importedText(plan)

    fun localCopy(themeId: String, local: List<LocalTheme>?, serverId: String? = null): LocalTheme? = PackageRules.localCopy(themeId, local, serverId)
}
