# Message queries

Read messages from a chat. All three functions are **asynchronous** — they take
a callback that receives `(messages, err)` once the data arrives from the server
or local cache.

> **Promise of a callback:** these functions **always** invoke your callback,
> either with results or with an error string. If something goes wrong at the
> network level, you get `(nil, "error text")` rather than silence.

---

## The message table

Every query returns an **array** (1-indexed) of message tables. Each message:

| Field | Type | Description |
|-------|------|-------------|
| `id` | number | Message id within the chat. |
| `text` | string\|nil | Text body, or `nil` if media-only. |
| `sender_id` | number | Dialog id of the sender (user positive, chat negative). `0` if unknown. |
| `chat_id` | number | Dialog id of the chat the message is in. |
| `date` | number | Unix timestamp. |
| `out` | boolean | `true` if sent by us. |
| `reply_to_msg_id` | number | Present only if this is a reply. |
| `media_type` | string | `"photo"`, `"document"`, or `"other"`. Absent for text-only. |
| `size` | number | (documents only) File size in bytes. |

---

## `xenon.getMessageById`

```lua
xenon.getMessageById(chatId, messageId, callback)
```

Fetches a single message by id.

| Param | Type | Description |
|-------|------|-------------|
| `chatId` | number | Dialog id containing the message. |
| `messageId` | number | Id of the message. |
| `callback` | function | `function(messages, err)` — `messages` is a 1-element array (or `nil`). |

```lua
xenon.getMessageById(ctx.chatId, ctx.reply_to_msg_id, function(orig, err)
    if err then xenon.toast("error: " .. err); return end
    if not orig or not orig[1] then xenon.toast("not found"); return end

    local m = orig[1]
    xenon.toast("replied-to: " .. (m.text or "(media)"))
    xenon.toast("was it ours? " .. tostring(m.out))
end)
```

> Use this to inspect a referenced message — e.g. checking whether a reply was
> addressed to **you** (`orig[1].out == true`).

---

## `xenon.getRecentMessages`

```lua
xenon.getRecentMessages(chatId, count, callback)
```

Fetches the last `count` messages in a chat, newest first.

| Param | Type | Description |
|-------|------|-------------|
| `chatId` | number | Dialog id. |
| `count` | number | How many recent messages to fetch (e.g. `20`). |
| `callback` | function | `function(messages, err)` — `messages` is the array (or `nil`). |

```lua
xenon.getRecentMessages(ctx.chatId, 10, function(msgs, err)
    if err or not msgs then return end

    -- collect everything sendable (text or reasonably-sized media)
    local sendable = {}
    for i = 1, #msgs do
        local m = msgs[i]
        local has_text = m.text and m.text ~= ""
        local has_media = m.media_type and m.media_type ~= ""
        local oversized = m.size and m.size > 10 * 1024 * 1024
        if has_text or (has_media and not oversized) then
            sendable[#sendable + 1] = m
        end
    end

    if #sendable > 0 then
        local pick = sendable[math.random(#sendable)]
        xenon.sendMessage(pick.text, ctx.chatId, ctx.msgId)
    end
end)
```

> The array is **newest first** (`msgs[1]` is the latest). Results come from the
> server, so it includes messages not yet in the local cache.

---

## `xenon.getMessagesFromUser`

```lua
xenon.getMessagesFromUser(chatId, userId, count, callback)
```

Like `getRecentMessages`, but filtered to messages from one specific user.

| Param | Type | Description |
|-------|------|-------------|
| `chatId` | number | Dialog id to search in. |
| `userId` | number | Sender's user id (**positive**). |
| `count` | number | How many messages to scan (then filter). |
| `callback` | function | `function(messages, err)` — only matching messages. |

```lua
-- find the last 5 things a specific user said in a group
xenon.getMessagesFromUser(-1001234567890, 3284201935, 50, function(msgs, err)
    if err or not msgs then return end
    for i = 1, #msgs do
        xenon.toast(msgs[i].text or "(media)")
    end
end)
```

> `count` is the scan window, not the result count. If you want 5 messages from
> a user who talks rarely, scan more (e.g. `100`). `userId` must be positive.

---

## Error handling pattern

All callbacks receive `(data, err)`. Always check both:

```lua
xenon.getRecentMessages(chatId, 10, function(msgs, err)
    if err ~= nil then
        -- network or permission error — err is a string like "CHAT_ADMIN_REQUIRED"
        xenon.toast("failed: " .. err)
        return
    end
    if msgs == nil then
        -- no data and no explicit error
        return
    end
    -- proceed with msgs[1..#msgs]
end)
```

| `data` | `err` | Meaning |
|--------|-------|---------|
| array | `nil` | Success — use it. |
| `nil` | string | Telegram returned an error. |
| `nil` | `nil` | Empty result / not found. |

---

## Common pitfalls

- **"Callback never runs"** → in older engine versions a missing peer (unopened
  private chat) could cause this. The current engine resolves peers from the DB
  automatically and always calls back; if you still see it, the `chatId` sign is
  likely wrong.
- **`orig[1]` is nil** → the message id doesn't exist in that chat (deleted, or
  belongs to a different chat).
- **Empty results** → for `getMessagesFromUser`, raise `count` — the target user
  may simply not appear in the scanned window.
