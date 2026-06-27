# Getting Started

This guide takes you from zero to a running plugin in a few minutes.
No Java, no build tools — just Lua and a text editor.

---

## 1. Prerequisites

- **Plugins enabled** in the app: *Xenon Settings → Plugins → Enable plugins*.
- A text editor (VS Code, Notepad++, vim, …).
- A way to get the file onto your device (USB, cloud, file manager).

> Lua knowledge helps but isn't required. The patterns below are copy-pasteable.

---

## 2. The smallest possible plugin

Create a file called `ping.xplugin` with this content:

```lua
plugin_id          = "ping"
plugin_name        = "Ping"
plugin_description = "Shows a toast when the app resumes"

xenon.on("onResume", function()
    xenon.toast("pong")
end)
```

That's it — manifest (`plugin_id`/`plugin_name`/`plugin_description`) plus one
hook handler. The `plugin_id` is the only field the engine strictly requires;
name/description are shown in the UI.

---

## 3. Anatomy of a plugin file

When the engine loads your `.xplugin`, it runs the whole file **once**, top to
bottom. Three things matter:

### a) Manifest — top-level variables

```lua
plugin_id          = "ping"        -- REQUIRED, unique, snake_case
plugin_name        = "Ping"        -- shown in the plugins list
plugin_description = "..."         -- shown under the name
```

### b) Hook handlers — `xenon.on(name, fn)`

Register a function for an event. **The plugin decides which events it cares
about** — you only subscribe to hooks you use. See [hooks.md](./hooks.md) for
the full list.

```lua
xenon.on("onNewMessage", function(ctx)
    if ctx.out then return end          -- ignore our own messages
    xenon.toast("got: " .. (ctx.text or ""))
end)
```

### c) Top-level code — runs once on load

Anything **outside** a function runs immediately when the plugin is installed or
the app starts. Use it to start timers/watchers, compute constants, etc.

```lua
local REACTIONS = {"🔥", "❤️", "😂"}    -- runs on load

xenon.on("onNewMessage", function(ctx)
    xenon.setReaction(ctx.chatId, ctx.msgId, REACTIONS[1])
end)
```

---

## 4. Install it

1. Transfer `ping.xplugin` to your device.
2. Open **Xenon Settings → Plugins**.
3. Tap **Install plugin…** and select the file.

If it installs, you'll see **Ping** in the list. Press the app's Home button and
come back — you should see the **pong** toast (that's `onResume` firing).

If installation fails, check `adb logcat -s XenonPlugin` — the most common cause
is a missing or duplicated `plugin_id`.

---

## 5. Make it configurable

Plugins can declare a settings UI that appears under the plugin's own settings
screen. Add a `plugin_settings` table:

```lua
plugin_settings = {
    {type = "toggle", key = "loud", name = "Loud mode", default = true},
    {type = "seekbar", key = "delay", name = "Delay (s)",
     min = 0, max = 30, step = 1, default = 2},
}

xenon.on("onResume", function()
    local loud = xenon.getSetting("loud", "true") == "true"
    if loud then
        xenon.toast("PONG!")
    end
end)
```

Note the gotcha: **settings are stored as strings.** `"true"`/`"false"` for
booleans, `"2"` for numbers — convert with `tonumber()` / string comparison.
See [settings.md](./settings.md).

---

## 6. React to a reply

A very common pattern: do something when **someone replies to your message**.
The `onNewMessage` hook gives you everything you need:

```lua
xenon.on("onNewMessage", function(ctx)
    if ctx.out then return end                              -- not our message
    if ctx.reply_to_msg_id == nil or ctx.reply_to_msg_id == 0 then
        return                                              -- not a reply at all
    end

    -- It IS a reply. Fetch the message it replies to:
    xenon.getMessageById(ctx.chatId, ctx.reply_to_msg_id, function(orig, err)
        if err or not orig or not orig[1] then return end
        if not orig[1].out then return end                  -- reply was not to us

        -- Reply-to-me confirmed — react + answer.
        xenon.setReaction(ctx.chatId, ctx.msgId, "🔥")
        xenon.sendMessage("got it", ctx.chatId, ctx.msgId)
    end)
end)
```

This is exactly the pattern the bundled `message_roulette.xplugin` uses.

---

## 7. Reload after editing

The engine doesn't hot-reload edited files. To test a change:

- **Reinstall** the file via the picker (overwrite), or
- Open the plugin's settings screen, which re-runs the file.

---

## 8. Debug checklist

| Symptom | Likely cause |
|---------|--------------|
| Install fails | Missing/duplicate `plugin_id`; Lua syntax error (see logcat) |
| Plugin loads, nothing happens | Hook name typo; `ctx` field name wrong |
| Callback "never fires" | Peer not loaded — usually a private chat (user id) you haven't opened; open the chat once, or the engine resolves it from the DB automatically |
| Reaction/send silently ignored | Wrong `chatId` convention — users are **positive** ids, chats/channels are **negative** |

Watch logs live:

```
adb logcat -s XenonPlugin
```

---

## Next steps

- [hooks.md](./hooks.md) — every event you can subscribe to.
- [messaging.md](./messaging.md) — send text/media/reactions.
- [manifest.md](./manifest.md) — full settings schema.
