package app.buddy.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives only Buddy's private notification action buttons. */
public final class RootApprovalReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !RootApproval.ACTION_ANSWER.equals(intent.getAction())) return;
        RootApproval.resolve(intent.getIntExtra(RootApproval.EXTRA_ID, -1),
                intent.getBooleanExtra(RootApproval.EXTRA_ALLOW, false));
    }
}
