# TT Calendar Sync

An Android app that copies events from your TimeTree calendars into a Google calendar on your phone,
one way and about every hour. Google Calendar then shows them on all your devices.

**Unofficial.** Not made by or affiliated with TimeTree. It uses an unofficial API that TimeTree may
change or block at any time. [Deutsche Kurzfassung unten.](#deutsch)

## What it does

- Signs in to TimeTree with your email and password and reads the calendars you pick.
- Copies their events into one Google calendar on the phone. Google Calendar's own sync uploads them
  to your Google account, like events you create yourself.
- Handles timed, all-day, multi-day and repeating events, edited or deleted occurrences, and deletions.
- Syncs every hour, or every 3 or 6 hours if you prefer. A small calendar is checked completely on
  every run, a larger one at least every 6 hours, so deletions arrive within that time. "Sync now"
  works at any time.
- Copies only events that ended no more than 30 days ago. Older copies are left as they are.
- Shows a status screen, a notification when syncing stops working, and a reliability checklist.
- English and German.

## What it doesn't do

- **It never writes to TimeTree.** Copying goes one way only. If you edit a copy, your edit lasts until
  the event changes in TimeTree. If you delete a copy, it comes back within a day. To stop copying a
  calendar, deselect it in the app.
- It doesn't copy birthdays, memos, comments, attachments, attendees, colours or reminders. Copies get
  the Google calendar's default notifications.
- It doesn't work with TimeTree accounts that sign in with Google or Apple (see
  [Requirements](#requirements) for the workaround).
- The target must be a Google calendar. Samsung, Outlook/Exchange or phone-only calendars aren't
  supported.
- It is not instant. New events show up within about an hour.

## Unofficial API: read this first

TimeTree offers no public API for this. The app uses the same web API as TimeTree's website, without
TimeTree's permission. This means:

- TimeTree can change that API at any time. Syncing then stops (the app tells you) until an app
  update fixes it, if one can.
- TimeTree's terms may not allow this kind of access, and TimeTree may block it. Use the app at your
  own risk.
- The app keeps its load low: it never syncs more often than once an hour and only reads.

Only copy calendars whose owners agreed. Their events end up in your Google account and are visible
to everyone you share that Google calendar with.

## Privacy

- **Your TimeTree password stays on your phone.** The app stores your email and password encrypted
  with a key that never leaves Android's Keystore. It needs them to sign in again when TimeTree ends
  the session (about every two weeks). They are sent only to TimeTree.
- **No server.** The data goes TimeTree → your phone → your Google calendar. There is no server of
  ours, no account with us, no analytics and no tracking.
- App data is excluded from backups. "Sign out" deletes the stored sign-in and all app data from the
  phone.
- The app's logs contain no passwords, cookies or event content.

## Requirements

- Android 8.0 or newer.
- A Google account on the phone with calendar sync turned on, and a Google calendar you can edit.
  Recommended: a separate calendar just for the copies. Create one on
  [calendar.google.com](https://calendar.google.com) (Settings → Add calendar → Create new calendar,
  for example "TimeTree"). It appears on the phone after Google's next sync.
- A TimeTree account that signs in with **email and password**.
  - Signed up with Google or Apple? Add an email address and a password to your TimeTree account,
    then sign in to this app with them:
    TODO(owner): exact menu path in the TimeTree app (e.g. Settings → Account → …).
  - Or ask the calendar's owner to invite a separate TimeTree account that uses email and password.

## Install

The app is not on Google Play. Install it from the APK on the releases page, or with Obtainium, which
also installs updates.

**From the releases page**

1. Open the releases page: TODO(owner): `https://github.com/<owner>/<repo>/releases`
2. Download `ttsync-<version>.apk` on your phone.
3. Open it. Android asks whether your browser (or file manager) may install unknown apps. Allow it,
   install, and turn the permission off again if you like.
4. Google Play Protect may warn about an app from an unknown developer. Choose to install anyway.

**With Obtainium (automatic updates)**

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Tap "Add app" and paste the repo URL: TODO(owner): `https://github.com/<owner>/<repo>`
3. Install. Obtainium checks for new releases and offers them as updates.

**Check that your copy is genuine.** In the app, open Settings → About and privacy and compare
"Signing certificate (SHA-256)" with this fingerprint:

```text
TODO(owner): release certificate SHA-256 (AB:CD:… form, see docs/RELEASING.md)
```

Android only installs updates that are signed with the same certificate.

## Setup

The app walks you through it:

1. **Sign in to TimeTree** with email and password.
2. **TimeTree calendars:** tick the calendars to copy.
3. **Target calendar:** allow calendar access, then pick the Google calendar for the copies. Each
   calendar shows how many other events it has; those stay untouched.
4. **Keep running:** allow unrestricted battery use (on Xiaomi, Redmi and POCO also Autostart).
5. **Status:** allow notifications, so the app can tell you when syncing stops. The first sync
   starts right away.

## Keep it running

Android, and some phone makers even more, stop apps in the background to save battery. If they stop
this app, syncing stops, and the app can't even notify you, because none of its code runs. Open
Status → **Reliability checklist** in the app: each row has a button to the right system screen.

- **Battery: Unrestricted.** App info → Battery → Unrestricted.
- **"Pause app activity if unused": off.** App info. Otherwise Android stops the app and removes its
  permissions if you don't open it for a few months.
- **Google sync: on.** Auto-sync and calendar sync for the Google account (Settings → Accounts).
  Otherwise the copies stay on the phone and never reach your Google account.
- **Samsung:** Settings → Battery (or Device care) → Background usage limits: turn off "Put unused
  apps to sleep", or add TT Calendar Sync to "Never sleeping apps". It must not be in "Sleeping apps"
  or "Deep sleeping apps".
- **Xiaomi, Redmi, POCO:** turn on **Autostart** for the app (App info → Autostart). Without it,
  background syncs never run. Also set Battery saver to "No restrictions".
- **OnePlus:** App info → Battery usage: allow background activity, and allow auto launch if the
  option is there. Older versions: Battery optimization → TT Calendar Sync → Don't optimize.
- **Other phones:** see [dontkillmyapp.com](https://dontkillmyapp.com) (the checklist links to your
  phone maker's page). Menu names differ between phone models and Android versions.

Now and then, look at Status: "Last successful sync" turns red when it is more than 24 hours old.

## Your other events are safe

The app changes or deletes only events it created itself. Each copy's description ends with a marker
line like `[ttsync:abc123…]`. The app touches an event only if it is in the target calendar and the
last line of its description is exactly the marker it expects. Everything else, including your own
events in the same calendar, is never changed or deleted.

Don't edit or remove the marker line. If you do, the app no longer treats that event as its copy: it
leaves it alone and creates a new copy.

## Uninstall

Android runs no app code during an uninstall, so uninstalling alone leaves the copies in your Google
calendar. Remove them first:

1. In the app, open Settings → **Remove all copies**. This deletes every copy the app made in the
   target calendar and nothing else. (Or Settings → Sign out, with "Also remove all copies" ticked.)
2. Then uninstall the app.

Already uninstalled? If you used a separate calendar for the copies, delete that calendar on
calendar.google.com. Otherwise install the app again, sign in, choose the same target calendar (the
app recognizes its copies by their marker), and then use Remove all copies.

## Troubleshooting

- **Sign-in rejected:** check email and password. Google or Apple sign-in doesn't work (see
  Requirements). After several failed attempts TimeTree may block sign-ins for a while.
- **Copies are on the phone but not on calendar.google.com:** Google sync is off; see the
  reliability checklist.
- **Syncing stopped:** see [Keep it running](#keep-it-running).
- **"TimeTree answered in a way this app doesn't understand":** TimeTree changed its API. Check for an
  app update.

## License and trademarks

Licensed under the [Apache License 2.0](LICENSE).

TimeTree is a trademark of TimeTree, Inc. TT Calendar Sync is an independent project and is not
affiliated with, endorsed or sponsored by TimeTree, Inc. Google and Google Calendar are trademarks of
Google LLC.

## For developers

Kotlin, Jetpack Compose, no server. Build with `ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug`.
Design and decisions: [docs/DESIGN.md](docs/DESIGN.md). Releases and signing:
[docs/RELEASING.md](docs/RELEASING.md). Read-only device check of the target calendar:
`scripts/device-verify.sh`.

## Deutsch

**TT Calendar Sync** kopiert die Termine ausgewählter TimeTree-Kalender etwa stündlich in einen
Google-Kalender auf deinem Android-Handy, nur in diese Richtung. Die App ist **inoffiziell**, gehört
nicht zu TimeTree und nutzt eine inoffizielle Schnittstelle, die TimeTree jederzeit ändern oder
sperren kann. Nutzung auf eigenes Risiko; kopiere nur Kalender, deren Besitzer zugestimmt haben.

- **Was sie nicht tut:** Sie schreibt nie in TimeTree. Änderungen an einer Kopie halten nur, bis sich
  der Termin in TimeTree ändert; gelöschte Kopien kommen zurück. Keine Geburtstage, Memos,
  Erinnerungen oder Teilnehmer.
- **Datenschutz:** E-Mail und Passwort bleiben auf dem Handy, verschlüsselt mit einem Schlüssel im
  Android-Keystore, und gehen nur an TimeTree. Kein eigener Server, keine Analyse, kein Tracking.
- **Voraussetzungen:** Android 8.0+, ein Google-Konto mit eingeschalteter Kalendersynchronisierung
  (am besten ein eigener Kalender, z. B. „TimeTree“), ein TimeTree-Konto mit E-Mail und Passwort.
  Wer sich bei TimeTree mit Google oder Apple anmeldet, legt in TimeTree eine E-Mail-Adresse und ein
  Passwort an: TODO(owner): genauer Menüpfad in der TimeTree-App.
- **Installation:** APK von der Releases-Seite laden und „Unbekannte Apps installieren“ für Browser
  oder Dateimanager erlauben, oder per [Obtainium](https://github.com/ImranR98/Obtainium) mit der
  Repo-URL (dann kommen Updates automatisch). Unter Einstellungen → „Über die App und Datenschutz“
  den Fingerabdruck des Signaturzertifikats mit dem oben genannten vergleichen.
- **Zuverlässig laufen lassen:** In der App die **Zuverlässigkeits-Checkliste** durchgehen:
  Akkunutzung „Uneingeschränkt“, „App-Aktivität bei Nichtnutzung pausieren“ aus, Google-Sync an.
  Samsung: in den Akku-Einstellungen (Hintergrundnutzung) die App als nie schlafend eintragen.
  Xiaomi/Redmi/POCO: **Autostart** an.
  OnePlus: Hintergrundaktivität erlauben. Mehr unter [dontkillmyapp.com](https://dontkillmyapp.com).
- **Deine Termine sind sicher:** Die App ändert oder löscht nur Termine, deren Beschreibung mit ihrer
  Markierungszeile `[ttsync:…]` endet. Diese Zeile bitte nicht ändern.
- **Deinstallieren:** Zuerst in den Einstellungen **„Alle Kopien entfernen“**, dann deinstallieren.
  Beim Deinstallieren selbst kann Android keine App-Funktion mehr ausführen, die Kopien blieben sonst
  im Kalender.

Lizenz: Apache 2.0. TimeTree ist eine Marke der TimeTree, Inc.; dieses Projekt steht in keiner
Verbindung zu TimeTree.
