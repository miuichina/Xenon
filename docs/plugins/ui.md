# UI

Show feedback to the user and present native pickers/dialogs. All UI functions
marshal onto the main thread internally, so call them from any hook.

---

## `xenon.toast`

```lua
xenon.toast(text)
```

Shows a small Android toast — the little bubble at the bottom of the screen.
Best for quick, unobtrusive feedback and **debugging**.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | ✅ | Message to show. |

Returns: nothing.

```lua
xenon.on("onNewMessage", function(ctx)
    xenon.toast("got msg " .. ctx.msgId)
end)
```

> Toasts disappear on their own (~2s) and don't capture focus. Safe to call
> frequently. For something more prominent, use `bulletin`.

---

## `xenon.bulletin`

```lua
xenon.bulletin(text)
```

Shows a **bulletin** — the Telegram-style banner that slides in below the action
bar with an icon. More visible than a toast.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | ✅ | Message to show. |

Returns: nothing.

```lua
xenon.bulletin("Roulette done for 3 chats!")
```

> Bulletins are global (shown at the app level), so they appear even if the user
> isn't on the chat your plugin acted on. They auto-dismiss after a few seconds.

---

## `xenon.createDialog`

```lua
xenon.createDialog(title, message, buttons)
```

Shows a centered alert dialog with configurable buttons.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `title` | string | ❌ | Dialog title (empty/`""` to hide). |
| `message` | string | ❌ | Body text (empty to hide). |
| `buttons` | table | ❌ | Button spec — see below. |

Returns: nothing.

`buttons` is a table with a **mixed** layout: array part holds button labels,
named keys hold their callbacks.

| Position / key | Meaning |
|----------------|---------|
| `buttons[1]` | Positive button label |
| `buttons["callback1"]` | Function run when positive is tapped |
| `buttons[2]` | Negative button label |
| `buttons["callback2"]` | Function run when negative is tapped |

```lua
xenon.createDialog(
    "Delete plugin data?",
    "This cannot be undone.",
    {
        [1] = "Delete",
        callback1 = function()
            xenon.setSetting("ids", "")
            xenon.toast("cleared")
        end,
        [2] = "Cancel",
        callback2 = function()
            -- nothing
        end,
    }
)
```

> Defaults: if you omit labels, they fall back to `"OK"` / `"Cancel"`. Omit
> `buttons` entirely for a single-OK informational dialog.

---

## `xenon.createBottomSheet`

```lua
xenon.createBottomSheet(title, items)
```

Shows a bottom sheet with a list of tappable rows.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `title` | string | ❌ | Sheet header (empty to hide). |
| `items` | table | ✅ | Array of item tables — see below. |

Returns: nothing.

Each item in `items`:

| Key | Type | Description |
|-----|------|-------------|
| `text` | string | Row label. |
| `callback` | function | Run when the row is tapped; the sheet closes after. |

```lua
xenon.createBottomSheet("Pick an action", {
    { text = "Send hello", callback = function() xenon.sendMessage("hi", chatId) end },
    { text = "React 🔥",  callback = function() xenon.setReaction(chatId, msgId, "🔥") end },
    { text = "Cancel",    callback = function() end },
})
```

> Rows are separated by divider lines automatically. The sheet dismisses after a
> row's callback runs.

---

## `xenon.openChatPicker`

```lua
xenon.openChatPicker(callback [, filter])
```

Opens Telegram's native **chat picker** (the dialogs list in selection mode).
Lets the user choose a chat; you get its dialog id back.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `callback` | function | ✅ | `function(chatId)` — called with the chosen dialog id. |
| `filter` | string | ❌ | Restrict the list: `"users"`, `"channels"`, or `"groups"`. Omit for all. |

Returns: nothing.

```lua
-- let the user pick a chat to add to a saved list
xenon.openChatPicker(function(chatId)
    xenon.setSetting("target", tostring(chatId))
    xenon.bulletin("Saved " .. chatId)
end, "users")
```

> `callback` receives a **single number** (the dialog id), not a table. If the
> user backs out without picking, the callback isn't called. The picker is a full
> screen, so it works from any hook.

---

## When to use which

| Goal | Use |
|------|-----|
| Quick debug value | `toast` |
| "Action done" confirmation | `bulletin` |
| Yes/no confirmation | `createDialog` |
| Choose from a few actions | `createBottomSheet` |
| Pick a chat | `openChatPicker` |

> **Tip:** during development, sprinkle `xenon.toast(...)` at each step of an
> async chain (e.g. inside callbacks) to see exactly where execution stops.
