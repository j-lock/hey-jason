# Hey, Jason 🐘

A personal reminder app for Android. Because an elephant never forgets.

## Get the app

Every change pushed to `main` is built automatically by GitHub Actions.
The newest version is always at:

**https://github.com/j-lock/hey-jason/releases/latest/download/HeyJason.apk**

Open that link on your phone, tap the downloaded file, and allow your browser to
"install unknown apps" the first time. New versions install right over the old
one, and your reminders are kept.

## What it does

- **Type it naturally:** "call mom tomorrow at 5pm", "dentist tue 3pm",
  "take pills every day 8am", "in 20 minutes check the oven".
- **Voice entry** with the 🎤 button.
- **Attachments:** photos from the camera or gallery, any document, plus a note.
  Copies are kept privately inside the app.
- **Two alert styles per reminder:** a normal notification, or an alarm that
  rings and fills the screen until you respond.
- **Snooze or finish from the notification:** Done, 10 minutes or 1 hour.
- **Repeats:** daily, weekly, monthly or yearly.
- Reminders survive restarts. Everything stays on the phone.

## First-run checklist (Pixel)

1. Allow notifications when asked.
2. If you use alarm-style reminders, allow full-screen notifications when the
   app asks, so the alarm can appear over your lock screen.

## Note on signing

`app/heyjason.keystore` is a signing key that's committed on purpose so every
build can update the previous one. It's only meant for this personally
sideloaded app; keep the repo private if you'd like.
