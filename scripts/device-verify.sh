#!/usr/bin/env bash
# Read-only check of the target calendar on a device (DESIGN.md section 10, checklist steps 1, 5, 6,
# 9, 11 and 13). It only runs `content query`; it never writes to the device.
#
# Usage:
#   ADB='ssh the test host adb -s <serial>' scripts/device-verify.sh <calendar_id>
#   ADB='ssh the test host adb -s <serial>' scripts/device-verify.sh -l      # list calendars and their ids
#
# ADB is the adb command prefix (default: adb). The queries are sent on stdin to `adb shell`, so
# they need no extra quoting when ADB goes through ssh.
#
# Output for <calendar_id> (rows with deleted=0):
# - our rows, whose description ends with the marker line `[ttsync:<uuid>]` (the app's strict rule):
#   _id, dtstart, dtend, duration, eventTimezone, allDay, rrule, exdate, dirty, _sync_id, marker.
#   Titles, notes and locations are not printed.
# - uuids that appear on more than one of our rows (duplicates);
# - the number of our rows that are deleted but not yet uploaded (deleted=1);
# - the number of other rows and a fingerprint of their content (title, times, recurrence, location,
#   description; not the sync columns). Run it before and after a sync: the same fingerprint means
#   the foreign events are unchanged (checklist step 6).

set -euo pipefail

usage() {
  sed -n '5,7p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

read -r -a adb <<<"${ADB:-adb}"

device() {
  # $1: script for the device shell, read from stdin by `adb shell`. \r: in case adb uses a pty.
  printf '%s\n' "$1" | "${adb[@]}" shell | tr -d '\r'
}

if [[ $# -ne 1 ]]; then usage; fi

if [[ $1 == -l ]]; then
  device "content query --uri content://com.android.calendar/calendars \
--projection _id:account_name:account_type:calendar_access_level:sync_events:calendar_displayName"
  exit 0
fi

cal=$1
[[ $cal =~ ^[0-9]+$ ]] || usage

events=content://com.android.calendar/events
# The free-text columns come last, so the columns before ", title=" never contain user text.
cols=_id:dtstart:dtend:duration:eventTimezone:allDay:rrule:exdate:dirty:_sync_id:title:eventLocation:description

out=$(device "
content query --uri content://com.android.calendar/calendars \
  --projection _id:account_type:calendar_access_level:sync_events:calendar_displayName --where \"_id=$cal\"
echo @@EVENTS
content query --uri $events --projection $cols --where \"calendar_id=$cal AND deleted=0\" --sort \"_id ASC\"
echo @@DELETED
content query --uri $events --projection _id --where \"calendar_id=$cal AND deleted=1 AND description LIKE '%[ttsync:%'\"
")

calendar=$(printf '%s\n' "$out" | sed -n '1,/^@@EVENTS$/p' | grep '^Row: ' || true)
if [[ -z $calendar ]]; then
  echo "Calendar $cal not found. List the calendars with: $0 -l" >&2
  exit 1
fi
echo "Calendar: ${calendar#Row: 0 }"

# Each event row starts with "Row: <n> _id=" and runs until the next one; multi-line descriptions
# continue on the following lines, so the description's last line is the row's last line.
# Prints "M<TAB>columns<TAB>uuid" for our rows, "U<TAB>line" for the content of other rows,
# "S" for other rows that contain "[ttsync:" but not as a valid last line, and "D" for each
# deleted=1 row of ours.
parsed=$(printf '%s\n' "$out" | awk '
  function flush(   first, last, uuid, cut, i) {
    if (n == 0) return
    first = line[1]
    sub(/^Row: [0-9]+ /, "", first)
    last = line[n]
    uuid = ""
    if (n == 1) {
      if (match(first, /, description=\[ttsync:[A-Za-z0-9_-]+\]$/))
        uuid = substr(first, RSTART + 22, RLENGTH - 23)
    } else if (last ~ /^\[ttsync:[A-Za-z0-9_-]+\]$/) {
      uuid = substr(last, 9, length(last) - 9)
    }
    if (uuid != "" && length(uuid) <= 64) {
      cut = index(first, ", title=")
      print "M\t" substr(first, 1, cut - 1) "\t" uuid
    } else {
      # Row indexes shift when our rows come and go, and Google sync changes dirty/_sync_id.
      sub(/, dirty=[^,]*, _sync_id=[^,]*/, "", first)
      print "U\t" first
      for (i = 2; i <= n; i++) print "U\t" line[i]
      print "U\t@@END"
      for (i = 1; i <= n; i++) if (index(line[i], "[ttsync:")) { print "S"; break }
    }
    n = 0
  }
  /^@@EVENTS$/  { section = "events"; next }
  /^@@DELETED$/ { flush(); section = "deleted"; next }
  section == "events" && /^Row: [0-9]+ _id=/ { flush() }
  section == "events" && !/^No result found\.$/ { line[++n] = $0 }
  section == "deleted" && /^Row: / { print "D" }
  END { flush() }
')

ours=$(printf '%s\n' "$parsed" | grep '^M' || true)
others=$(printf '%s\n' "$parsed" | grep -c '^U	@@END$' || true)
suspicious=$(printf '%s\n' "$parsed" | grep -c '^S$' || true)
deleted=$(printf '%s\n' "$parsed" | grep -c '^D$' || true)

if [[ -z $ours ]]; then
  echo "Our rows: 0"
else
  echo "Our rows: $(printf '%s\n' "$ours" | wc -l)"
  printf '%s\n' "$ours" | awk -F '\t' '{ print "  " $2 ", marker=[ttsync:" $3 "]" }'
  dups=$(printf '%s\n' "$ours" | cut -f3 | sort | uniq -c | awk '$1 > 1 { print "  " $2 " x" $1 }')
  if [[ -z $dups ]]; then echo "Duplicate markers: none"; else printf 'Duplicate markers:\n%s\n' "$dups"; fi
fi
echo "Our rows deleted, upload pending (deleted=1): $deleted"

if command -v sha256sum >/dev/null; then sha=(sha256sum); else sha=(shasum -a 256); fi
fingerprint=$(printf '%s\n' "$parsed" | grep '^U' | "${sha[@]}" | cut -c1-16)
echo "Other rows: $others (content fingerprint $fingerprint)"
if [[ $suspicious -gt 0 ]]; then
  echo "  $suspicious of them contain \"[ttsync:\" but not as the last line, so the app leaves them alone."
fi
