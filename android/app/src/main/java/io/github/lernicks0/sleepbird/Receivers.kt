package io.github.lernicks0.sleepbird

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { NotificationEngine.receive(context, intent) }
}

class SleepActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("night")?.let { NotificationEngine.complete(context, it) }
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Reset spacing after manually changing the system clock; date keys stay intact.
        val store = SleepStore(context)
        if (intent.action == Intent.ACTION_TIME_CHANGED) store.lastDelivered = 0
        NotificationEngine.reconcile(context, store)
    }
}
