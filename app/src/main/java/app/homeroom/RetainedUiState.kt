package app.homeroom

import androidx.lifecycle.ViewModel

/** Memory-only UI state that survives a configuration recreation of MainActivity. Never persisted. */
class RetainedUiState : ViewModel() {
    /** Typed Feedback text while the page was open when the system recreated the activity. */
    var feedbackDraft: String? = null
}
