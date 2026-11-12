# TT Calendar Sync

An Android app that copies the events of your TimeTree calendars into a Google calendar on your
phone. Google Calendar then shows them on all your devices, next to your other calendars.

- **One way only:** TimeTree → your Google calendar. The app only reads from TimeTree and never
  changes anything there.
- **No server:** everything runs on your phone. No account with us, no analytics, no tracking.
- **Unofficial:** not made by or affiliated with TimeTree. It uses TimeTree's unofficial web API,
  which can change or stop working at any time. See [Things to know](#things-to-know).

The app is in English and German. [Deutsche Kurzfassung unten.](#deutsch)

## What it does

- Signs in to TimeTree with your email and password and reads the calendars you pick.
- Copies their events into one Google calendar on the phone. Android's Google Calendar sync uploads
  them to your Google account, just like events you create yourself.
- Keeps the copies up to date: new, changed and deleted events; timed, all-day, multi-day and
  repeating events; moved or deleted dates of a repeating event.
- Syncs in the background about every hour (or every 3 or 6 hours, if you prefer). "Sync now" works
  at any time.
- Copies everything that ended at most 30 days ago or lies in the future. Older copies are left as
  they are.

## What it doesn't do

- **It never writes to TimeTree.** If you edit a copy in Google Calendar, your edit lasts only until
  the event changes in TimeTree. If you delete a copy, it comes back. To stop copying a calendar,
  untick it in the app.
- It doesn't copy birthdays, memos, comments, attachments, attendees, colours or reminders. Copies
  get the Google calendar's default notifications.
- It can't sign in with Google or Apple. There is a
  [workaround](#signed-up-for-timetree-with-google-or-apple).
- The target must be a Google calendar. Samsung, Outlook/Exchange and phone-only calendars aren't
  supported.
- It isn't instant: changes arrive with the next sync, usually within the hour.
- It isn't on Google Play, and there is no iPhone version.

## Requirements

- Android 8.0 or newer.
- A Google account on the phone with calendar sync turned on, and a Google calendar you can edit.
- A TimeTree account that signs in with **email and password**.

### Signed up for TimeTree with Google or Apple?

The app can't use Google or Apple sign-in. Add an email address and a password to your TimeTree
account instead:

1. Open the TimeTree app and tap **More** (bottom right).
2. Tap **Manage Account** → **Change email address** and set your email address.
3. Back in **Manage Account**, tap **Change password** and set a password.
4. Sign in to TT Calendar Sync with that email address and password.

Or ask the calendar's owner to invite a second TimeTree account that uses email and password.

## Install

TT Calendar Sync isn't on Google Play. Install it from the
[releases page](https://github.com/tylerstyle/ttsync/releases), or with Obtainium, which also keeps
it updated.

**From the releases page**

1. On your phone, open <https://github.com/tylerstyle/ttsync/releases> and download
   `ttsync-<version>.apk` from the latest release.
2. Open the downloaded file. Android asks whether your browser (or file manager) may install unknown
   apps. Allow it and install. You can turn the permission off again afterwards.
3. Google Play Protect may warn you about an app from an unknown developer. Choose to install
   anyway.

**With Obtainium (automatic updates)**

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Tap **Add App** and enter the source URL `https://github.com/tylerstyle/ttsync`.
3. Install. Obtainium checks for new releases and offers them as updates.

**Check that your copy is genuine.** Every release is signed with the same key. Its certificate
fingerprint (SHA-256) is:

```text
C0:FD:19:AE:C1:8E:BB:8F:5B:B2:59:5D:4A:91:B1:19:4C:C8:9D:67:03:4C:32:FF:73:7F:E3:7A:A6:39:C1:13
```

- **In the app:** open Settings → **About and privacy** and compare "Signing certificate (SHA-256)"
  with the fingerprint above.
- **On a computer (optional):** each release lists the APK's SHA-256 in its notes and in a `.sha256`
  file. Compare it with `sha256sum ttsync-<version>.apk` (macOS: `shasum -a 256`, Windows:
  `certutil -hashfile ttsync-<version>.apk SHA256`). With the Android SDK, `apksigner verify
  --print-certs ttsync-<version>.apk` shows the certificate fingerprint in lower case without
  colons: `c0fd19aec18ebb8f5bb2595d4a91b1194cc89d67034c32ff737fe37aa639c113`.

If the fingerprint doesn't match, uninstall the app. Android installs an update only if it is signed
with the same key as the installed app, so once you have a genuine copy, nobody else can replace it.

## Setup

The app walks you through it:

1. **Sign in to TimeTree** with your email and password.
2. **TimeTree calendars:** tick the calendars to copy. Only pick calendars whose owners agree to
   having their events copied.
3. **Target calendar:** allow calendar access, then pick the Google calendar for the copies. Each
   calendar shows how many other events it has; the app never touches those.

   **Tip: use a separate calendar just for the copies.** Create one on
   [calendar.google.com](https://calendar.google.com) (Settings → Add calendar → Create new
   calendar, for example "TimeTree"), wait until it shows up in the app, and tap Refresh. That keeps
   the copies apart from your own events, and you can hide, share or delete them all at once.
4. **Keep running:** allow unrestricted battery use (on Xiaomi, Redmi and POCO, also turn on
   Autostart). You can skip this, but then Android may stop the background syncs, and Status shows
   a warning.
5. **Status:** allow notifications, so the app can tell you when syncing stops working. The first
   sync starts right away.

## Everyday use

You don't need to open the app; it syncs in the background. The **Status** screen (the app's home
screen) shows:

- whether syncing works ("Syncing normally", "Needs your attention", …), with a button to fix a
  problem;
- the last successful sync (in red when it's more than 24 hours ago) and the next one;
- what the last sync did: added, updated, deleted, skipped;
- warnings, for example when Google sync is off or battery use is restricted;
- **Sync now**, to sync right away. If the last sync was less than a minute ago, the app just tells
  you so.

**How often it syncs.** Every hour by default. You can choose every 3 or 6 hours in Settings → Sync
interval, but never more often than hourly, to keep the load on TimeTree low. Android may run a
sync up to 15 minutes early or late, and it waits while the phone is offline.

New and changed events arrive with the next sync. For deleted events it depends on the size of the
TimeTree calendar: a small calendar (up to a few hundred events, past ones included) is read
completely on every sync, so deletions arrive within the sync interval too. A larger calendar is
read completely every 6 hours, so a deletion there can take up to 6 hours.

**Notifications.** The app only notifies you when something needs your attention:

- syncing failed 3 times in a row, or hasn't succeeded for more than 24 hours;
- the app lost access to a TimeTree calendar (for example, you were removed from it). Its copies are
  kept, and the notification offers to remove them.

The next successful sync clears the notification. While a sync is running, Android may also show a
short "Syncing with TimeTree" notification.

**Settings** (gear icon on Status): sync interval, TimeTree calendars, target calendar, the
reliability checklist, **Full resync** (reads everything again and repairs copies that were changed
or deleted), **Remove all copies**, **Sign out**, and **About and privacy**.

## Keep it running

Android, and some phone makers even more, stop apps in the background to save battery. If they stop
this app, syncing stops, and the app can't even warn you, because none of its code runs. Open Status
→ **Reliability checklist**: every item has a button that opens the right system screen.

- **Battery: Unrestricted.** App info → Battery → Unrestricted.
- **"Pause app activity if unused": off.** In App info. Otherwise Android stops the app and removes
  its permissions if you don't open it for a few months.
- **Google sync: on.** Auto-sync and calendar sync for your Google account (Settings → Accounts).
  Otherwise the copies stay on the phone and never reach your Google account.
- **Samsung:** Settings → Battery (or Device care) → Background usage limits: turn off "Put unused
  apps to sleep", or add TT Calendar Sync to "Never sleeping apps". It must not be in "Sleeping
  apps" or "Deep sleeping apps".
- **Xiaomi, Redmi, POCO:** turn on **Autostart** for the app (App info → Autostart). Without it,
  background syncs never run. Also set Battery saver to "No restrictions".
- **OnePlus:** App info → Battery usage: allow background activity, and allow auto launch if the
  option is there. On older versions: Battery optimization → TT Calendar Sync → Don't optimize.
- **Other phones:** see [dontkillmyapp.com](https://dontkillmyapp.com); the checklist links to the
  page for your phone's maker. Menu names differ between phone models and Android versions.

Now and then, glance at Status: "Last successful sync" turns red when it is more than 24 hours old.

## Privacy

- **Your password stays on your phone.** The app stores your TimeTree email and password encrypted
  with a key that is kept in Android's Keystore and never leaves it. It needs them to sign in again
  when TimeTree ends the session (about every two weeks). They are sent only to TimeTree.
- **No server, no tracking.** Your data goes TimeTree → your phone → your Google calendar. There is
  no server of ours, no account with us, no analytics, no crash reporting and no ads.
- **Network connections.** The app itself connects only to TimeTree (`timetreeapp.com`): to sign in
  and to read your calendar list, labels and events. Uploading the copies to Google is done by
  Android's own Google Calendar sync, not by the app. The links in the app (dontkillmyapp.com, the
  source code) open in your browser, and only when you tap them.
- App data is excluded from Android backups. **Sign out** deletes the stored sign-in, the settings
  and the sync data from the phone.
- The app's logs contain no passwords, cookies or event content.
- The copied events end up in your Google account and are visible to everyone you share that Google
  calendar with. That's another reason to copy only calendars whose owners agree.

## Your other events are safe

The app changes or deletes only events it created itself. Each copy's description ends with a
marker line like `[ttsync:0a1b2c…]`. The app touches an event only if it is in the target calendar
and the last line of its description is exactly the marker the app expects. Everything else,
including your own events in the same calendar, is never changed or deleted.

Don't edit or remove the marker line. If you do, the app no longer treats that event as its copy:
it leaves it alone and creates a new copy.

## Stop syncing or uninstall

- **Stop copying one calendar:** Settings → TimeTree calendars, untick it and save. The app asks,
  then removes that calendar's copies.
- **Stop everything:** Settings → **Remove all copies**. It deletes every copy the app made in the
  target calendar, and nothing else, and stops syncing. You stay signed in.
- **Sign out:** Settings → Sign out. Tick "Also remove all copies" to remove the copies as well;
  otherwise they stay in your calendar.
- **Uninstall:** use **Remove all copies** first, then uninstall. Android runs no app code during an
  uninstall, so uninstalling alone leaves the copies in your Google calendar.

Already uninstalled? If you used a separate calendar for the copies, delete that calendar on
calendar.google.com. Otherwise install the app again, sign in, choose the same target calendar (the
app recognizes its copies by their marker) and then use Remove all copies.

## Troubleshooting and FAQ

**"TimeTree rejected the sign-in."** Check your email and password. Accounts that sign in with
Google or Apple need the [workaround](#signed-up-for-timetree-with-google-or-apple). After several
failed attempts, TimeTree may block sign-ins for a while, so wait before trying again.

**"TimeTree rejected the stored sign-in" or "TimeTree needs you to sign in again".** You probably
changed your TimeTree password, or the session ended. Tap **Sign in again** and sign in.

**"TimeTree asked the app to slow down" or "No connection to TimeTree".** Nothing to do; the app
tries again by itself.

**"TimeTree answered in a way this app doesn't understand."** TimeTree changed its API. Check for an
app update (Obtainium or the releases page). If there is none, please
[open an issue](https://github.com/tylerstyle/ttsync/issues).

**"Target calendar was deleted" or "The target calendar can't be written any more".** Choose a target
calendar again (the button on Status takes you there).

**"Google sync is off" or "changes not uploaded to Google yet".** The copies are on the phone but
don't reach your Google account. Turn on auto-sync and calendar sync for your Google account
(Settings → Accounts).

**"No access to a TimeTree calendar".** You were removed from that calendar, or it was deleted. The
app keeps its copies until you remove them (button on Status or in the notification).

**Syncing stopped, or "Last successful sync" is red.** Go through [Keep it running](#keep-it-running).

**An event is missing.** Birthdays and memos aren't copied, nor are events that ended more than 30
days ago. Otherwise tap Sync now, and check that its calendar is ticked under Settings → TimeTree
calendars.

**My edit to a copy was undone, or a deleted copy came back.** That's expected: copying goes one way
only, and TimeTree wins. Change the event in TimeTree instead, or untick the calendar.

**How do I get reminders for the copies?** Copies use the default notifications of the Google
calendar. Set them on calendar.google.com: Settings → your calendar → Event notifications.

**I see an event twice.** Usually the marker line of one copy was changed, so the app made a new
copy. Delete the one without a valid marker line.

**Reporting a problem.** [Open an issue](https://github.com/tylerstyle/ttsync/issues) with your phone
model, Android version, app version (Settings → About and privacy) and the message from Status.
Never post your password or private event details.

## Things to know

TimeTree offers no public API for this. The app uses the same web API as TimeTree's website, without
TimeTree's permission. This means:

- TimeTree can change that API at any time. Syncing then stops (the app tells you) until an app
  update fixes it, if one can.
- TimeTree's terms of service may not allow this kind of access, and TimeTree may block it. Check
  the terms yourself; **use the app at your own risk.**
- The app keeps its load low: it only reads, waits at least a second between requests and never
  syncs more often than once an hour.
- The app is provided "as is", without warranty of any kind (see the [license](LICENSE)). Keep your
  own backup of anything important.

## Disclaimer and license

TT Calendar Sync is an independent, unofficial project. It is **not affiliated with, endorsed or
sponsored by TimeTree, Inc.** TimeTree is a trademark of its owner. Google, Google Calendar and
Android are trademarks of Google LLC. All other trademarks belong to their owners.

Licensed under the [Apache License 2.0](LICENSE). Third-party libraries and their licenses:
[docs/LICENSES.md](docs/LICENSES.md).

## For developers

Want to help or build it yourself? Start with [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): how the
project is put together, how to build and test it, and how to contribute. More background:
[design record](docs/DESIGN.md), [on-device tests](docs/DEVICE-TESTS.md),
[release process](docs/RELEASING.md).

## Deutsch

**TT Calendar Sync** kopiert die Termine ausgewählter TimeTree-Kalender in einen Google-Kalender auf
deinem Android-Handy, etwa stündlich und nur in diese Richtung. Die App liest nur aus TimeTree und
ändert dort nie etwas. Es gibt keinen Server: alles läuft auf dem Handy.

**Inoffiziell:** Die App steht in keiner Verbindung zu TimeTree, Inc. und wird von TimeTree weder
unterstützt noch gesponsert. Sie nutzt TimeTrees inoffizielle Web-Schnittstelle, die TimeTree
jederzeit ändern oder sperren kann; die Nutzungsbedingungen von TimeTree erlauben das womöglich
nicht. Nutzung auf eigenes Risiko. Kopiere nur Kalender, deren Besitzer zugestimmt haben.

- **Was sie nicht tut:** Sie schreibt nie in TimeTree. Änderungen an einer Kopie halten nur, bis
  sich der Termin in TimeTree ändert; gelöschte Kopien kommen zurück. Keine Geburtstage, Memos,
  Erinnerungen, Anhänge oder Teilnehmer.
- **Voraussetzungen:** Android 8.0 oder neuer, ein Google-Konto mit eingeschalteter
  Kalendersynchronisierung, ein TimeTree-Konto mit E-Mail und Passwort.
- **Anmeldung mit Google oder Apple bei TimeTree?** Lege in der TimeTree-App eine E-Mail-Adresse
  und ein Passwort an: „More“ (unten rechts) → „Manage Account“ → „Change email address“ (E-Mail
  festlegen), dann „Change password“ (Passwort festlegen). Danach meldest du dich in TT Calendar
  Sync mit dieser E-Mail und diesem Passwort an. In einer deutschsprachigen TimeTree-App können die
  Menüpunkte übersetzt heißen.
- **Installation:** Die APK von der [Releases-Seite](https://github.com/tylerstyle/ttsync/releases)
  laden und „Unbekannte Apps installieren“ für Browser oder Dateimanager erlauben. Oder mit
  [Obtainium](https://github.com/ImranR98/Obtainium) und der URL
  `https://github.com/tylerstyle/ttsync`, dann kommen Updates automatisch. Unter Einstellungen →
  „Über die App und Datenschutz“ muss der Fingerabdruck des Signaturzertifikats lauten:
  `C0:FD:19:AE:C1:8E:BB:8F:5B:B2:59:5D:4A:91:B1:19:4C:C8:9D:67:03:4C:32:FF:73:7F:E3:7A:A6:39:C1:13`.
- **Einrichtung:** Anmelden, TimeTree-Kalender auswählen, Ziel-Google-Kalender wählen (am besten
  einen eigenen, z. B. „TimeTree“, auf calendar.google.com anlegen), Akkunutzung
  „Uneingeschränkt“ erlauben, Benachrichtigungen erlauben. Die erste Synchronisierung startet
  sofort.
- **Im Alltag:** Der Status-Bildschirm zeigt die letzte erfolgreiche Synchronisierung (rot nach mehr
  als 24 Stunden) und hat „Jetzt synchronisieren“. Synchronisiert wird stündlich (wahlweise alle 3
  oder 6 Stunden). Gelöschte Termine verschwinden bei kleinen Kalendern mit der nächsten
  Synchronisierung, bei großen spätestens nach 6 Stunden.
- **Zuverlässig laufen lassen:** In der App die **Zuverlässigkeits-Checkliste** durchgehen:
  Akkunutzung „Uneingeschränkt“, „App-Aktivität bei Nichtnutzung pausieren“ aus, Google-Sync an.
  Samsung: die App bei den Hintergrundnutzungslimits als nie schlafend eintragen.
  Xiaomi/Redmi/POCO: **Autostart** an. OnePlus: Hintergrundaktivität erlauben. Mehr unter
  [dontkillmyapp.com](https://dontkillmyapp.com).
- **Datenschutz:** E-Mail und Passwort bleiben auf dem Handy, verschlüsselt mit einem Schlüssel im
  Android-Keystore, und gehen nur an TimeTree. Die App verbindet sich nur mit `timetreeapp.com`;
  das Hochladen zu Google übernimmt die Google-Kalendersynchronisierung von Android. Kein eigener
  Server, keine Analyse, kein Tracking.
- **Deine Termine sind sicher:** Die App ändert oder löscht nur Termine, deren Beschreibung mit
  ihrer Markierungszeile `[ttsync:…]` endet. Diese Zeile bitte nicht ändern.
- **Beenden und deinstallieren:** Zuerst in den Einstellungen **„Alle Kopien entfernen“**, dann
  deinstallieren. Beim Deinstallieren kann Android keinen App-Code mehr ausführen, die Kopien
  blieben sonst im Kalender.

Lizenz: [Apache License 2.0](LICENSE). TimeTree ist eine Marke ihres Inhabers; Google, Google
Kalender und Android sind Marken von Google LLC.
