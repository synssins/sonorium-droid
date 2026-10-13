package app.sonorium.ui.coach

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sonorium.R

/** The guides and the controls they point at (targets: [Targets]). Texts are in strings.xml. */
object Guides {
    /** First run, over the main screen (Chris's steps and wording). */
    const val FIRST_RUN = "first-run"

    @Composable
    fun firstRun() = CoachGuide(
        FIRST_RUN,
        listOf(
            CoachStep(Targets.PLAY, stringResource(R.string.coach_play_title), stringResource(R.string.coach_play_text)),
            CoachStep(Targets.SERVERS, stringResource(R.string.coach_connections_title), stringResource(R.string.coach_connections_text)),
            CoachStep(Targets.SERVERS, stringResource(R.string.coach_add_title), stringResource(R.string.coach_add_text)),
        ),
    )

    /** The guide a page's help offers again ([view]: the help's page), or null. */
    @Composable
    fun forHelp(view: String): CoachGuide? = when (view) {
        "sessions" -> firstRun()
        else -> null
    }
}

/** Names of the controls guides point at ([coachTarget]). */
object Targets {
    const val PLAY = "play"
    const val SERVERS = "servers"
}
