package app.buddy.assistant;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Receives only Buddy's private notification action buttons and inline replies. */
public final class RootApprovalReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !RootApproval.ACTION_ANSWER.equals(intent.getAction())) return;
        int id = intent.getIntExtra(RootApproval.EXTRA_ID, -1);
        String choice = intent.getStringExtra(RootApproval.EXTRA_ANSWER);
        if (choice != null) {
            RootApproval.resolveAnswer(id, choice);
            return;
        }
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results != null) {
            CharSequence reply = results.getCharSequence(RootApproval.EXTRA_REPLY);
            if (reply != null) {
                RootApproval.resolveAnswer(id, reply.toString());
                return;
            }
        }
        RootApproval.resolve(id, intent.getBooleanExtra(RootApproval.EXTRA_ALLOW, false));
    }
}
