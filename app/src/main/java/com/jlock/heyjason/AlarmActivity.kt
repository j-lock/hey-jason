package com.jlock.heyjason

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Full-screen alarm that appears over the lock screen. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        Store.init(this)

        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val r = Store.get(id)
        if (r == null) {
            finish()
            return
        }
        val early = intent.getIntExtra(Scheduler.EXTRA_MINUTES, -1)
        val ctx = this
        setContent {
            HeyJasonTheme {
                if (early >= 0) {
                    AlarmScreen(
                        r,
                        earlyMinutes = early,
                        onPrimary = {
                            Notifs.cancelEarly(ctx, id)
                            finish()
                        },
                        onSecondary = {
                            Actions.doneEarly(ctx, id)
                            finish()
                        },
                    )
                } else {
                    AlarmScreen(
                        r,
                        onPrimary = {
                            Actions.doneFromAlert(ctx, id)
                            finish()
                        },
                        onSecondary = {
                            Actions.snooze(ctx, id, 10)
                            finish()
                        },
                    )
                }
            }
        }
    }
}
