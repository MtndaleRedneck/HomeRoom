package app.homeroom

import android.view.View
import android.widget.Button
import android.widget.ScrollView
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// NATIVE graphics: the gate asserts real child/viewport geometry, and legacy
// shadow mode reports 0 heights (every true-expecting assertion failed while
// every false-expecting one passed) - same mode as the other geometry tests.
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActionReviewGateTest {
    // The ScrollView must be ATTACHED to a window: under NATIVE graphics,
    // measure/layout of a detached view is a no-op, leaving the child at
    // height 0 and the gate fail-closed regardless of content.
    private fun gate(contentHeight:Int):Triple<ScrollView,Button,ActionReviewScrollGate> {
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val scroll=ScrollView(activity);scroll.addView(View(activity),android.widget.FrameLayout.LayoutParams(300,contentHeight))
        activity.setContentView(scroll)
        val confirm=Button(activity)
        val gate=ActionReviewScrollGate(scroll,confirm)
        scroll.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY))
        scroll.layout(0,0,300,200)
        // Run-87 GATE-DIAG: attached scroll at 200px but child never measured
        // (childH=0 childMeasured=0) - the window does not traverse this
        // replaced content in the sandbox. Give the child its geometry
        // explicitly; idempotent where the framework already measured it.
        val child=scroll.getChildAt(0)
        child.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(contentHeight,View.MeasureSpec.EXACTLY))
        child.layout(0,0,300,contentHeight)
        gate.refresh()
        return Triple(scroll,confirm,gate)
    }
    @Test fun longContentBlocksConfirmBeforeBottomAndAllowsItAtBottom() {
        val (scroll,confirm,gate)=gate(900)
        assertFalse(confirm.isEnabled);assertFalse(gate.canConfirm())
        scroll.scrollTo(0,699);gate.refresh();assertFalse(confirm.isEnabled)
        scroll.scrollTo(0,700);gate.refresh();assertTrue(confirm.isEnabled);assertTrue(gate.canConfirm())
        gate.setBusy(true);assertFalse(confirm.isEnabled);assertFalse(gate.canConfirm())
        gate.setBusy(false);assertTrue(confirm.isEnabled)
        scroll.scrollTo(0,0);gate.refresh();assertFalse(confirm.isEnabled)
    }
    @Test fun contentThatFitsNeedsNoScrolling() {
        val (_,confirm,gate)=gate(150)
        assertTrue(confirm.isEnabled);assertTrue(gate.canConfirm())
    }
    @Test fun unmeasuredContentAndIncreasedContentFailClosed() {
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val scroll=ScrollView(activity);scroll.addView(View(activity));val confirm=Button(activity)
        val gate=ActionReviewScrollGate(scroll,confirm);assertFalse(gate.canConfirm());assertFalse(confirm.isEnabled)
        val (measured,button,g)=gate(150);assertTrue(button.isEnabled)
        measured.getChildAt(0).layout(0,0,300,900);g.refresh();assertFalse(button.isEnabled)
    }
    @Test fun resumeHidesExpiredNeverAttemptedButPreservesUncertainExactReplay() {
        val now=java.time.Instant.parse("2026-11-11T18:00:00Z")
        val saved=JSONObject().put("attempted",false).put("expires","2026-11-11T17:59:59Z")
        assertFalse(decisionReviewResumable(saved,now))
        saved.put("expires",now.toString());assertFalse(decisionReviewResumable(saved,now))
        saved.put("expires","2026-11-11T18:00:01Z");assertTrue(decisionReviewResumable(saved,now))
        saved.put("expires","bad");assertFalse(decisionReviewResumable(saved,now))
        saved.put("attempted",true);assertTrue(decisionReviewResumable(saved,now))
    }
    @Test fun statusUsesPlainLabelsNotWireEnums() {
        assertFalse(actionApprovalStateLabel("authorized").contains("authorized"))
        assertTrue(actionApprovalStateLabel("outcome_unknown").contains("do not retry"))
    }
}
