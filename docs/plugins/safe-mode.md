# Safe Mode

Safe Mode is a recovery option for the plugin engine. If a plugin ever breaks
the app — freezes it, crashes it, or just misbehaves — Safe Mode lets you get
back in with **all plugins disabled**, so you can fix or remove the offending one.

---

## How to start in Safe Mode

> **Hold a volume button (up or down) while the app is opening.**

Steps:
1. Fully close the app (swipe it away from recents — not just background it).
2. Press and **hold** a volume key.
3. While still holding, tap the app icon to launch it.
4. Keep holding until the app finishes opening.

When Safe Mode activates:
- All plugins are **disabled** for that session (and the setting is saved).
- A **"Crashed!"-style sheet** appears confirming Safe Mode is on.
- You can then open **Settings → Plugins**, fix or remove the bad plugin, and
  re-enable the engine.

---

## When to use it

- The app **freezes on the logo** at launch (a plugin is hanging the UI thread).
- The app **crashes immediately** after start.
- A plugin is misbehaving and you can't reach its settings normally.

Safe Mode only disables the plugin engine — your chats, account, and other
settings are untouched.

---

## Automatic recovery

You don't always have to trigger Safe Mode manually. The engine also recovers
on its own:

- **After a crash:** if the app crashed on the previous launch, the next launch
  automatically disables plugins and shows a sheet with a copyable crash log.
- **After a hang:** if the previous launch never finished starting (froze, was
  killed, or hit an ANR), the next launch detects it and disables plugins.

So in most cases, just **relaunching** the app after a crash/freeze is enough —
plugins will already be off. The manual volume-key method is for when you want
to force it, or when automatic detection didn't catch the problem.

---

## Re-enabling plugins

Once you're back in:
1. Open **Settings → Plugins**.
2. Find the plugin that caused the problem and turn its toggle **off** (or
   delete it with the trash icon).
3. Turn **Enable plugins** back on.

Individual plugin toggles work even while the engine is off, so you can pick
exactly which plugins load before re-enabling the whole engine.

---

## Copying the crash log

When Safe Mode shows a sheet after a crash, tap **Copy crash log** to copy the
full error to your clipboard — useful for reporting the issue to the plugin's
author or the Xenon developers. The log is also saved to
`plugin_crash.txt` in the app's internal storage.
