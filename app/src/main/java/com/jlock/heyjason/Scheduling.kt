package com.jlock.heyjason

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

object Scheduler {
    const val ACTION_FIRE = "com.jlock.heyjason.FIRE"
    const val ACTION_DONE = "com.jlock.heyjason.DONE"
    const val ACTION_SNOOZE = "com.jlock.heyjason.SNOOZE"
    const val EXTRA_ID = "id"
    const val EXTRA_SNOOZE = "snooze"
    const val EXTRA_MINUTES = "minutes"
    const val EXTRA_EARLY = "early"
    const val ACTION_EARLY = "com.jlock.heyjason.EARLY"
    const val ACTION_DISMISS = "com.jlock.heyjason.DISMISS"
    const val MAX_EARLY = 8
    private const val SNOOZE_OFFSET = 1_000_000

    private fun earlyPi(ctx: Context, id: Int, slot: Int, minutes: Int): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .setAction(ACTION_EARLY)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_MINUTES, minutes)
        return PendingIntent.getBroadcast(ctx, 10_000_000 + id * 10 + slot, i, IMMUTABLE)
    }

    private fun cancelEarly(ctx: Context, am: AlarmManager, id: Int) {
        for (slot in 0 until MAX_EARLY) am.cancel(earlyPi(ctx, id, slot, 0))
    }

    private fun firePi(ctx: Context, id: Int, snooze: Boolean): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_SNOOZE, snooze)
        return PendingIntent.getBroadcast(ctx, if (snooze) id + SNOOZE_OFFSET else id, i, IMMUTABLE)
    }

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(firePi(ctx, r.id, false))
        cancelEarly(ctx, am, r.id)
        val now = System.currentTimeMillis()
        if (r.done || r.timeMillis <= now) return
        setExact(ctx, am, r.timeMillis, firePi(ctx, r.id, false), r.alarm, r.id)
        r.early.distinct().take(MAX_EARLY).forEachIndexed { slot, minutes ->
            val at = r.timeMillis - minutes * 60_000L
            if (at > now) setExact(ctx, am, at, earlyPi(ctx, r.id, slot, minutes), r.alarm, r.id)
        }
    }

    /** A one-off snooze that doesn't disturb a repeating reminder's schedule. */
    fun scheduleSnooze(ctx: Context, r: Reminder, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        setExact(ctx, am, at, firePi(ctx, r.id, true), r.alarm, r.id)
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(firePi(ctx, id, false))
        am.cancel(firePi(ctx, id, true))
        cancelEarly(ctx, am, id)
    }

    private fun setExact(ctx: Context, am: AlarmManager, at: Long, pi: PendingIntent, alarm: Boolean, id: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                return
            }
            if (alarm) {
                val show = PendingIntent.getActivity(
                    ctx, id,
                    Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ID, id),
                    IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** After a reboot, time change or app update, put every alarm back. */
    fun rescheduleAll(ctx: Context) {
        Store.init(ctx)
        val now = System.currentTimeMillis()
        Store.items.value.forEach { r ->
            if (r.done) return@forEach
            if (r.repeat != Repeat.NONE && r.timeMillis <= now) {
                val nr = r.copy(timeMillis = Recurrence.next(r.timeMillis, r.repeat, now))
                Store.upsert(nr)
                schedule(ctx, nr)
            } else {
                schedule(ctx, r)
            }
        }
    }
}

object Notifs {
    private const val CH_REMIND = "reminders"
    private const val CH_ALARM = "alarms_v1"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val remind = NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Regular reminders"
            enableVibration(true)
        }
        val alarm = NotificationChannel(CH_ALARM, "Alarm reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders that ring like an alarm until you respond"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
        }
        nm.createNotificationChannels(listOf(remind, alarm))
    }

    fun show(ctx: Context, r: Reminder) {
        val content = PendingIntent.getActivity(
            ctx, r.id,
            Intent(ctx, MainActivity::class.java)
                .putExtra(Scheduler.EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            IMMUTABLE
        )

        fun action(action: String, minutes: Int, code: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, r.id * 10 + code,
            Intent(ctx, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(Scheduler.EXTRA_ID, r.id)
                .putExtra(Scheduler.EXTRA_MINUTES, minutes),
            IMMUTABLE
        )

        val body = if (r.note.isNotBlank()) r.title + "\n" + r.note else r.title
        val extras = buildString {
            if (r.attachments.isNotEmpty()) append("\n📎 ${r.attachments.size} attached — tap to open")
        }
        val b = NotificationCompat.Builder(ctx, if (r.alarm) CH_ALARM else CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_elephant)
            .setContentTitle("Hey, Jason! ${r.emoji}")
            .setContentText(r.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body + extras))
            .setColor(0xFFF4A259.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (r.alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(content)
            .setAutoCancel(true)
            .addAction(0, "✓ Done", action(Scheduler.ACTION_DONE, 0, 1))
            .addAction(0, "Snooze 10m", action(Scheduler.ACTION_SNOOZE, 10, 2))
            .addAction(0, "1 hour", action(Scheduler.ACTION_SNOOZE, 60, 3))

        if (r.alarm) {
            val full = PendingIntent.getActivity(
                ctx, r.id + 2_000_000,
                Intent(ctx, AlarmActivity::class.java)
                    .putExtra(Scheduler.EXTRA_ID, r.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                IMMUTABLE
            )
            b.setFullScreenIntent(full, true).setOngoing(true)
        }

        val n = b.build()
        if (r.alarm) n.flags = n.flags or Notification.FLAG_INSISTENT
        try {
            NotificationManagerCompat.from(ctx).notify(r.id, n)
        } catch (e: SecurityException) {
            // Notifications not allowed — nothing we can do from here.
        }
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    private fun earlyNid(id: Int) = id + 500_000

    fun cancelEarly(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(earlyNid(id))

    /** "Heads up, Jason!" — the early warning before the real time. */
    fun showEarly(ctx: Context, r: Reminder, minutes: Int) {
        val content = PendingIntent.getActivity(
            ctx, r.id,
            Intent(ctx, MainActivity::class.java)
                .putExtra(Scheduler.EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            IMMUTABLE
        )
        val gotIt = PendingIntent.getBroadcast(
            ctx, r.id * 10 + 4,
            Intent(ctx, ReminderReceiver::class.java)
                .setAction(Scheduler.ACTION_DISMISS)
                .putExtra(Scheduler.EXTRA_ID, r.id),
            IMMUTABLE
        )
        val doneAlready = PendingIntent.getBroadcast(
            ctx, r.id * 10 + 5,
            Intent(ctx, ReminderReceiver::class.java)
                .setAction(Scheduler.ACTION_DONE)
                .putExtra(Scheduler.EXTRA_ID, r.id)
                .putExtra(Scheduler.EXTRA_EARLY, true),
            IMMUTABLE
        )
        val whenText = "${describe(r.timeMillis)} (in ${earlyLabel(minutes)})"
        val body = buildString {
            append(r.title).append("\n").append(whenText.replaceFirstChar { it.uppercaseChar() })
            if (r.note.isNotBlank()) append("\n").append(r.note)
        }
        val b = NotificationCompat.Builder(ctx, if (r.alarm) CH_ALARM else CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_elephant)
            .setContentTitle("Heads up, Jason! ${r.emoji}")
            .setContentText("${r.title} · in ${earlyLabel(minutes)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(0xFFF4A259.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (r.alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(content)
            .setAutoCancel(true)
            .addAction(0, "👍 Got it", gotIt)
            .addAction(0, "✓ Done already", doneAlready)

        if (r.alarm) {
            val full = PendingIntent.getActivity(
                ctx, r.id + 4_000_000,
                Intent(ctx, AlarmActivity::class.java)
                    .putExtra(Scheduler.EXTRA_ID, r.id)
                    .putExtra(Scheduler.EXTRA_MINUTES, minutes)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                IMMUTABLE
            )
            b.setFullScreenIntent(full, true).setOngoing(true)
        }
        val n = b.build()
        if (r.alarm) n.flags = n.flags or Notification.FLAG_INSISTENT
        try {
            NotificationManagerCompat.from(ctx).notify(earlyNid(r.id), n)
        } catch (e: SecurityException) {
            // Notifications not allowed.
        }
    }
}

/** Everything that changes a reminder's state goes through here. */
object Actions {
    private fun now() = System.currentTimeMillis()

    fun save(ctx: Context, r: Reminder) {
        val fixed = if (r.repeat != Repeat.NONE && r.timeMillis <= now())
            r.copy(timeMillis = Recurrence.next(r.timeMillis, r.repeat, now())) else r
        Store.upsert(fixed)
        Scheduler.schedule(ctx, fixed)
    }

    fun fired(ctx: Context, id: Int, isSnooze: Boolean) {
        Store.init(ctx)
        val r = Store.get(id) ?: return
        if (r.done) return
        Notifs.show(ctx, r)
        if (!isSnooze && r.repeat != Repeat.NONE) {
            val nr = r.copy(timeMillis = Recurrence.next(r.timeMillis, r.repeat, now()))
            Store.upsert(nr)
            Scheduler.schedule(ctx, nr)
        }
    }

    /** "Done" from a notification or the alarm screen. */
    fun doneFromAlert(ctx: Context, id: Int) {
        Store.init(ctx)
        Notifs.cancel(ctx, id)
        val r = Store.get(id) ?: return
        if (r.repeat == Repeat.NONE) {
            Store.upsert(r.copy(done = true, completedAt = now()))
            Scheduler.cancel(ctx, id)
        }
    }

    /** A heads-up is due. Skipped if the reminder was finished or moved meanwhile. */
    fun early(ctx: Context, id: Int, minutes: Int) {
        Store.init(ctx)
        val r = Store.get(id) ?: return
        if (r.done || r.timeMillis <= now() || minutes !in r.early) return
        Notifs.showEarly(ctx, r, minutes)
    }

    /** "Done already" from a heads-up: counts as finishing the upcoming occurrence. */
    fun doneEarly(ctx: Context, id: Int) {
        Store.init(ctx)
        Notifs.cancelEarly(ctx, id)
        val r = Store.get(id) ?: return
        complete(ctx, r)
    }

    /** Ticked off in the list. A repeating one skips ahead to its next time. */
    fun complete(ctx: Context, r: Reminder) {
        Notifs.cancel(ctx, r.id)
        Notifs.cancelEarly(ctx, r.id)
        if (r.repeat == Repeat.NONE) {
            Store.upsert(r.copy(done = true, completedAt = now()))
            Scheduler.cancel(ctx, r.id)
        } else {
            val nr = r.copy(timeMillis = Recurrence.next(r.timeMillis, r.repeat, maxOf(now(), r.timeMillis)))
            Store.upsert(nr)
            Scheduler.schedule(ctx, nr)
        }
    }

    fun uncomplete(ctx: Context, r: Reminder) {
        val nr = r.copy(done = false, completedAt = 0L)
        Store.upsert(nr)
        Scheduler.schedule(ctx, nr)
    }

    fun snooze(ctx: Context, id: Int, minutes: Int) {
        Store.init(ctx)
        Notifs.cancel(ctx, id)
        val r = Store.get(id) ?: return
        val at = now() + minutes * 60_000L
        if (r.repeat == Repeat.NONE) {
            val nr = r.copy(timeMillis = at, done = false)
            Store.upsert(nr)
            Scheduler.schedule(ctx, nr)
        } else {
            Scheduler.scheduleSnooze(ctx, r, at)
        }
    }

    fun delete(ctx: Context, id: Int) {
        Scheduler.cancel(ctx, id)
        Notifs.cancel(ctx, id)
        Notifs.cancelEarly(ctx, id)
        Store.delete(id)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        if (id < 0) return
        when (intent.action) {
            Scheduler.ACTION_FIRE -> Actions.fired(ctx, id, intent.getBooleanExtra(Scheduler.EXTRA_SNOOZE, false))
            Scheduler.ACTION_DONE ->
                if (intent.getBooleanExtra(Scheduler.EXTRA_EARLY, false)) Actions.doneEarly(ctx, id)
                else Actions.doneFromAlert(ctx, id)
            Scheduler.ACTION_EARLY -> Actions.early(ctx, id, intent.getIntExtra(Scheduler.EXTRA_MINUTES, 0))
            Scheduler.ACTION_DISMISS -> Notifs.cancelEarly(ctx, id)
            Scheduler.ACTION_SNOOZE -> Actions.snooze(ctx, id, intent.getIntExtra(Scheduler.EXTRA_MINUTES, 10))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifs.createChannels(ctx)
        Scheduler.rescheduleAll(ctx)
    }
}
