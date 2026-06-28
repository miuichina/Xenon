# Messaging

Send text, forward media, set reactions, and mark chats as read. These are
**outgoing actions** your plugin triggers (as opposed to hooks, which react to
incoming events).

All functions in this group are **fire-and-forget**: they schedule the action
and return immediately. They marshal onto the UI thread internally, so you can
call them from any hook.

---

## `xenon.sendMessage`

```lua
xenon.sendMessage(text, chatId [, replyToMsgId [, callback]])
```

Sends a plain-text message to a chat. Optionally as a reply, and optionally
with a callback that receives the server-assigned message id (useful if you
need to delete or edit the message later).

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | ✅ | Message body. |
| `chatId` | number | ✅ | Destination dialog id. **Users positive**, chats/channels **negative**. |
| `replyToMsgId` | number | ❌ | Message id to reply to. Omit or `0` for no reply. |
| `callback` | function | ❌ | `function(msgId)` — called with the sent message's id, or `nil` on failure. |

Returns: nothing.

```lua
-- simple send
xenon.sendMessage("hello", 3284201935)

-- reply to a specific message
xenon.sendMessage("agreed", -1001234567890, 42)

-- reply inside onNewMessage
xenon.on("onNewMessage", function(ctx)
    if ctx.out then return end
    xenon.sendMessage("noted", ctx.chatId, ctx.msgId)
end)

-- send + get the id, then delete it after 2 seconds
xenon.sendMessage("oops", chatId, 0, function(msgId)
    if msgId == nil then return end
    xenon.setTimeout(2, function()
        xenon.deleteMessage(chatId, msgId)
    end)
end)
```

> If the chat isn't in memory (common for private chats not opened this
> session), the engine resolves it from the local database before sending. If it
> still can't be resolved, the send is skipped and the callback (if any) gets
> `nil`.

---

## `xenon.sendMedia`

```lua
xenon.sendMedia(chatId, msgId [, replyToMsgId])
```

Forwards the **media** of an existing message (`msgId` from `chatId`) into
`chatId`. Works with photos and documents (files, GIFs, videos, voice).

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `chatId` | number | ✅ | Both the source chat and the destination. |
| `msgId` | number | ✅ | Id of the message whose media to forward. |
| `replyToMsgId` | number | ❌ | Message id to reply to. |

Returns: nothing.

```lua
-- forward the media of message 7 as a reply to message 3
xenon.sendMedia(ctx.chatId, 7, 3)
```

> The message's **caption** is carried over; the source message must have media.
> Text-only messages produce no output (use `sendMessage` for those). Messages
> larger than ~10 MB are usually fine, but very large files may fail to forward
> if the file reference expired — re-fetch and retry if needed.

---

## `xenon.setReaction`

```lua
xenon.setReaction(chatId, msgId, reaction)
```

Puts a reaction emoji on a message.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `chatId` | number | ✅ | Dialog id containing the message. |
| `msgId` | number | ✅ | Message id to react to. |
| `reaction` | string | ✅ | A single emoji, e.g. `"🔥"`, `"❤️"`. Empty string removes the reaction. |

Returns: nothing.

```lua
local REACTIONS = {"🔥", "❤️", "😂", "👍"}

xenon.on("onNewMessage", function(ctx)
    if ctx.out then return end
    xenon.setReaction(ctx.chatId, ctx.msgId, REACTIONS[math.random(#REACTIONS)])
end)
```

> Only built-in emoji reactions are supported. Custom/premium animated reactions
> are not. Reacting to a message you already reacted to replaces the reaction.

---

## `xenon.readHistory`

```lua
xenon.readHistory(chatId)
```

Marks all messages in a chat as read (sends a read receipt).

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `chatId` | number | ✅ | Dialog id to mark read. |

Returns: nothing.

```lua
xenon.on("onNewMessage", function(ctx)
    if ctx.out then return end
    xenon.readHistory(ctx.chatId)
end)
```

> In channels, this also clears the unread counter. It does not affect messages
> that arrive after the call.

---

## `xenon.deleteMessage`

```lua
xenon.deleteMessage(chatId, msgId)
```

Deletes a message. For private chats and basic groups, it deletes for everyone
(`revoke = true`). For channels/supergroups it uses `channels.deleteMessages`.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `chatId` | number | ✅ | Dialog id containing the message. |
| `msgId` | number | ✅ | Id of the message to delete. |

Returns: nothing.

```lua
-- send a message, then delete it after 2 seconds
xenon.sendMessage("temporary", chatId, 0, function(msgId)
    if msgId == nil then return end
    xenon.setTimeout(2, function()
        xenon.deleteMessage(chatId, msgId)
    end)
end)
```

> You can only delete messages you have permission to delete (your own, or ones
> you're admin for). In basic groups, `revoke` deletes for everyone if the group
> allows it. Pair with `sendMessage`'s callback to get the id of a message you
> just sent.

---

## Dialog id cheat sheet

Every chat is identified by a single number. The **sign** tells you the type:

| Type | Example | Notes |
|------|---------|-------|
| Private chat (user) | `3284201935` (positive) | The user's id. |
| Basic group | `-12345678` (negative) | `-` group id. |
| Supergroup / channel | `-1001234567890` (negative) | `-100` prefix + channel id. |

The same `chatId` you get from hooks (`ctx.chatId`) works directly as the
`chatId` argument to every function here.

---

## Common pitfalls

- **"Nothing happens"** → the chat id sign is wrong, or the peer isn't loaded.
  Open the chat once in the app, or rely on the engine's automatic DB lookup.
- **`sendMessage` doesn't reply** → you passed `replyToMsgId` as the second
  argument instead of the third. Signature is `sendMessage(text, chatId, replyTo)`.
- **Reaction ignored** → you passed a multi-character string or a non-emoji.
  Use exactly one emoji.
