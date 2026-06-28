# Xenon Plugins

Xenon has a built-in (vibecoded) plugin engine that lets anyone extend the app with **Lua** scripts.
Plugins are plain `.xplugin` files — no APK rebuild, no Java, no compilation.

> **TL;DR:** write a `.lua` file, rename it to `.xplugin`, drop it into the app via
> *Xenon Settings → Plugins → Install*. Done.

---

## What a plugin is

A plugin is a single Lua source file with:

1. A **manifest** — three top-level variables declaring who the plugin is.
2. An optional **settings schema** — `plugin_settings`, a table describing the UI
   shown in the plugin's own settings screen.
3. **Hook handlers** — registered with `xenon.on("eventName", function(ctx) ... end)`.
4. **Top-level code** — runs once when the plugin loads (timers, watchers, setup).

```lua
plugin_id          = "hello_world"          -- REQUIRED, unique, snake_case
plugin_name        = "Hello World"
plugin_description = "My first Xenon plugin"

xenon.on("onResume", function()
    xenon.toast("Hello from a plugin!")
end)
```

That's a complete, working plugin.

---

## The `xenon` table

Everything the host exposes lives under the global `xenon` table:

| Area | Functions |
|------|-----------|
| [Hooks](./hooks.md) | `on` |
| [Messaging](./messaging.md) | `sendMessage`, `sendMedia`, `setReaction`, `readHistory`, `deleteMessage` |
| [Message queries](./message-queries.md) | `getMessageById`, `getRecentMessages`, `getMessagesFromUser` |
| [Settings & storage](./settings.md) | `getSetting`, `setSetting`, `refreshSettings` |
| [Timers & watchers](./timers.md) | `setTimeout`, `clearTimeout`, `startMessageWatcher`, `stopMessageWatcher` |
| [UI](./ui.md) | `toast`, `bulletin`, `createDialog`, `createBottomSheet`, `openChatPicker`, `promptText` |
| [App integration](./app-integration.md) | `getOpenChatId`, `getPeerName`, `openActivity`, `openPluginSettings`, `finish` |
| [Manifest](./manifest.md) | `plugin_id`, `plugin_name`, `plugin_description`, `plugin_settings` |

---

## How to install & test

1. Save your script as `my_plugin.xplugin` (extension **must** be `.xplugin`).
2. Open **Xenon Settings → Plugins**.
3. Turn on **Enable plugins** (top toggle).
4. Tap **Install plugin…** and pick the file.
5. If the plugin has a manifest, it appears in the list and runs immediately.

### Reloading

Plugins load once at app start. To pick up changes to a file:

- Reinstall it via the picker (overwrite), **or**
- Toggle a plugin off and back on in its settings screen.

### Debugging

- Use `xenon.toast("msg")` or `xenon.bulletin("msg")` for on-device feedback.
- Watch `adb logcat -s XenonPlugin` for the engine's diagnostic output (hook
  registration, fires, errors). Your `print()` output is not captured — use the
  API functions above instead.

---

## Rules & conventions

- **File extension:** `.xplugin`
- **`plugin_id` is mandatory.** A plugin without it is rejected at install time.
  Use stable, unique ids — they identify your plugin for updates.
- **Each plugin is sandboxed:** it gets its own Lua globals and its own settings
  namespace (settings are stored as `"<fileName>_<key>"`).
- **Hooks are the only way** to react to the app. The host fires them; your
  plugin decides which to subscribe to. See [hooks.md](./hooks.md).
- **Thread safety:** hook handlers run on the thread that fired them (often the
  UI thread). API functions that need the UI thread marshal themselves there;
  you don't have to.

---

## Example plugins

A starter that sends "Test" to a chat on every app resume:

```lua
plugin_id          = "resume_sender"
plugin_name        = "Resume Sender"
plugin_description = "Sends Test on every onResume"

local TARGET_CHAT = 3284201935

xenon.on("onResume", function()
    xenon.sendMessage("Test", TARGET_CHAT)
end)
```

A full-featured example ships in `TMessagesProj/src/main/assets/plugins/message_roulette.xplugin`.
