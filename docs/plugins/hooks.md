# Hooks

Hooks are **events** the host app fires at meaningful moments. Your plugin
subscribes to the ones it cares about with `xenon.on(name, handler)`.

```lua
xenon.on("onResume", function(ctx)
    -- ctx depends on the hook; onResume passes nothing.
    xenon.toast("back in the app")
end)
```

The plugin chooses what to listen to — you only register handlers for hooks you
actually use. Unsubscribed hooks cost nothing.

---

## Registering a handler

```lua
xenon.on(hookName, handler)
```

| Param | Type | Description |
|-------|------|-------------|
| `hookName` | string | One of the hook names below. |
| `handler` | function | Called when the hook fires. Receives a `ctx` table (except where noted). |

`handler` can return a value for hooks marked **return** below — the host uses
that value (e.g. to block or rewrite an action).

---

## Hook reference

### `onResume`

Fires every time the app returns to the foreground (activity resumed).

- **ctx:** none (handler takes no args)
- **returns:** ignored

```lua
xenon.on("onResume", function()
    xenon.sendMessage("I'm back", 3284201935)
end)
```

---

### `onNewMessage`

Fires for **every** new message that reaches the account — incoming and outgoing,
private chats, groups, and channels.

- **ctx fields:**

| Field | Type | Description |
|-------|------|-------------|
| `chatId` | number | Dialog id. **Users are positive**, chats/channels **negative**. |
| `msgId` | number | Message id (within the chat). |
| `text` | string\|nil | Message text, or `nil` if media-only. |
| `reply_to_msg_id` | number | Id of the message this replies to, or `0`. |
| `out` | boolean | `true` if the message was sent **by us**. |

- **returns:** ignored

```lua
xenon.on("onNewMessage", function(ctx)
    if ctx.out then return end                       -- ignore our own
    if ctx.text and string.find(ctx.text, "hello") then
        xenon.sendMessage("hi!", ctx.chatId, ctx.msgId)
    end
end)
```

> Fired on the **UI thread**. `ctx` is a snapshot — it won't update later.

---

### `onSendMessage`

Fires **before** the user's outgoing text message is actually sent. Lets a
plugin **rewrite** the text/destination or **cancel** the send entirely.

- **ctx fields:**

| Field | Type | Description |
|-------|------|-------------|
| `message` | string | The text about to be sent. |
| `peer` | number | Destination dialog id (user = positive, chat = negative). |

- **returns:** a table to control the send, or `nil` to leave it unchanged.

| Return shape | Effect |
|--------------|--------|
| `nil` / nothing | Send unchanged. |
| `{ cancel = true }` | Abort the send. |
| `{ message = "..." }` | Rewrite the text. |
| `{ peer = <number> }` | Redirect to another chat. |
| `{ message = "...", peer = <number> }` | Rewrite text and redirect. |

```lua
xenon.on("onSendMessage", function(ctx)
    -- append a signature to everything we send
    return { message = ctx.message .. " 🤖", peer = ctx.peer }
end)

xenon.on("onSendMessage", function(ctx)
    -- block sending a secret word
    if ctx.message == "password" then
        xenon.toast("blocked!")
        return { cancel = true }
    end
end)
```

> **First plugin wins.** Handlers are called in plugin order; the first one to
> return a non-nil table decides. The others are skipped for that send.

---

### `onChatMenuBuild`

Fires when the chat header menu is built, letting a plugin **add items** to the
menu of every open chat.

- **ctx fields:**

| Field | Type | Description |
|-------|------|-------------|
| `peer` | number | Dialog id of the chat the menu belongs to. |

- **returns:** an array of menu item tables, or `nil` to add nothing.

Each item:

| Key | Type | Description |
|-----|------|-------------|
| `text` | string | Label shown in the menu (default `"Plugin"`). |
| `icon` | string | Optional icon name — see [app-integration.md](./app-integration.md) for the icon list. |

```lua
xenon.on("onChatMenuBuild", function(ctx)
    return {
        { text = "Say hi",  icon = "send" },
        { text = "Settings", icon = "settings" },
    }
end)
```

---

### `onChatMenuItemClick`

Fires when the user taps one of **your** menu items (those returned from
`onChatMenuBuild`).

- **ctx fields:**

| Field | Type | Description |
|-------|------|-------------|
| `item` | number | 1-based index of the tapped item (matches the order you returned). |
| `peer` | number | Dialog id of the chat the menu was open in. |

- **returns:** ignored

```lua
xenon.on("onChatMenuItemClick", function(ctx)
    if ctx.item == 1 then
        xenon.sendMessage("hi!", ctx.peer)
    elseif ctx.item == 2 then
        xenon.openPluginSettings()
    end
end)
```

> After the click handler runs, the host rebuilds the plugin menu, so
> `onChatMenuBuild` fires again — useful if your items depend on state.

---

## Quick reference table

| Hook | When | ctx has | Can return |
|------|------|---------|------------|
| `onResume` | app returns to foreground | — | — |
| `onNewMessage` | any message arrives | `chatId, msgId, text, reply_to_msg_id, out` | — |
| `onSendMessage` | before our text is sent | `message, peer` | `{cancel/message/peer}` |
| `onChatMenuBuild` | chat menu is built | `peer` | array of items |
| `onChatMenuItemClick` | our menu item tapped | `item, peer` | — |

---

## Conventions across hooks

- **`chatId` / `peer` / dialog id** all mean the same thing: positive for users,
  negative for chats/channels. This is consistent everywhere in the API.
- **`out`** consistently means "sent by us".
- **`ctx` is read-only-ish:** treat it as a snapshot. Mutating it has no effect
  on the app.
- **Errors are contained:** if your handler throws a Lua error, only that plugin
  is affected — the host and other plugins keep running. The error is logged to
  `adb logcat -s XenonPlugin`.
