package app.homeroom

import androidx.lifecycle.ViewModel

/** Memory-only UI state that survives a configuration recreation of MainActivity. Never persisted. */
class RetainedUiState : ViewModel() {
    /** Typed Feedback text while the page was open when the system recreated the activity. */
    var feedbackDraft: String? = null
    /** A shared file Uri waiting for unlock (never bytes or room names). */
    var pendingShare: android.net.Uri? = null
    /** Feedback send identity: kept while the outcome is unknown, so a retry of the same text replays. */
    var feedbackClientId: String? = null
    var feedbackSentText: String? = null
}
