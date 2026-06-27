# App integration

Miscellaneous functions for reading app state, navigating within the app, and
styling your UI elements with built-in icons.

---

## `xenon.getOpenChatId`

```lua
xenon.getOpenChatId()
```

Returns the dialog id of the chat the user currently has open, or `0` if no chat
is open (e.g. they're on the chats list).

Returns: number.

```lua
local function add_current_chat()
    local chatId = xenon.getOpenChatId()
    if chatId == 0 then
        xenon.bulletin("Open a chat first!")
        return
    end
    xenon.setSetting("target", tostring(chatId))
end
```

> Tracks the **foreground** chat. If the user switches chats, a subsequent call
> returns the new one. Returns `0` on the dialog list / settings screens.

---

## `xenon.getPeerName`

```lua
xenon.getPeerName(chatId)
```

Returns the display name of a chat's peer (cached in memory), or `nil` if
unknown.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `chatId` | number | ✅ | Dialog id. |

Returns: string or `nil`.

```lua
local function add_chat_with_name(chatId)
    local name = xenon.getPeerName(chatId)
    if not name then name = tostring(chatId) end
    xenon.bulletin("Added " .. name)
end
```

> Returns the **cached** name only (no network fetch). For users it's
> `"First Last"`; for chats/channels it's the title. If you just installed the
> plugin and never opened the chat, this may return `nil` — open the chat first
> or fall back to the numeric id.

---

## `xenon.openActivity`

```lua
xenon.openActivity(name)
```

Navigates to a built-in Xenon screen.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | ✅ | One of `"settings"` or `"plugins"`. |

Returns: nothing.

```lua
xenon.openActivity("plugins")
```

| `name` | Opens |
|--------|-------|
| `"settings"` | Xenon settings root |
| `"plugins"` | Plugins manager |

> Unknown names are ignored (logged to logcat). This launches the main
> activity with an intent action, so it works from any hook.

---

## `xenon.openPluginSettings`

```lua
xenon.openPluginSettings()
```

Opens **this plugin's** own settings screen (the one rendered from
`plugin_settings`). Takes no args — it always opens the settings of the plugin
that calls it.

Returns: nothing.

```lua
xenon.on("onChatMenuItemClick", function(ctx)
    if ctx.item == 3 then
        xenon.openPluginSettings()
    end
end)
```

> Handy from a chat menu item: give the user a "Settings" entry that jumps
> straight into your plugin's configuration.

---

## `xenon.finish`

```lua
xenon.finish()
```

Signals that the current plugin settings screen should close after its action
finishes. Call it at the end of a `button` `action` if you want tapping the
button to also dismiss the settings screen.

Returns: nothing.

```lua
{type = "button", key = "done", name = "I'm done configuring", action = function()
    xenon.setSetting("configured", "true")
    xenon.finish()
end}
```

> This is a **request**, not a force — it sets a flag the settings screen checks.
> It only has an effect while the plugin's settings screen is open.

---

## Icon names

Several places accept an `icon` name as a string — most notably menu items built
via the [`onChatMenuBuild`](./hooks.md#onchatmenubuild) hook. The engine maps the
name to a built-in drawable.

```lua
xenon.on("onChatMenuBuild", function(ctx)
    return {
        { text = "Send",    icon = "send" },
        { text = "Settings", icon = "settings" },
    }
end)
```

### Available icon names

| Name | | Name | |
|------|-|------|-|
| `add` | ➕ | `mute` | 🔇 |
| `autodelete` | ⏲ | `pin` | 📌 |
| `background` | 🖼 | `report` | ⚑ |
| `block` | 🚫 | `saved` | 💾 |
| `calendar` | 📅 | `search` | 🔍 |
| `call` | 📞 | `send` | ➤ |
| `cancel` | ✖ | `settings` | ⚙ |
| `channel` | 📢 | `share` | ↗ |
| `clear` | 🧹 | `stats` | 📊 |
| `copy` | ⧉ | `sticker` | 🎴 |
| `delete` | 🗑 | `theme` | 🎨 |
| `discussion` | 💬 | `topic` | 🗂 |
| `edit` | ✎ | `translate` | 🌐 |
| `fave` / `heart` | ❤ | `videocall` | 📹 |
| `unfave` | 🤍 | `vote` | ✓ |
| `forward` | ↪ | | |
| `help` | ? | | |
| `home` | 🏠 | | |
| `info` | ℹ | | |
| `leave` | 🚪 | | |
| `link` | 🔗 | | |
| `log` | 📜 | | |

> An unknown or empty `icon` name falls back to the `edit` (pencil) icon. Icon
> appearance follows the current theme color.

---

## Quick reference

| Function | Returns | Purpose |
|----------|---------|---------|
| `getOpenChatId()` | number | Which chat is open (or `0`) |
| `getPeerName(chatId)` | string\|nil | Cached name of a peer |
| `openActivity(name)` | — | Open Xenon settings / plugins screen |
| `openPluginSettings()` | — | Open this plugin's settings screen |
| `finish()` | — | Request the settings screen to close |
