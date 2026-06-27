# Timers & watchers

Schedule delayed work, and poll chats for new messages on an interval. These
power "do X every N seconds" features without your plugin needing to run a loop.

---

## `xenon.setTimeout`

```lua
local id = xenon.setTimeout(seconds, callback)
```

Runs `callback` once, after `seconds` seconds. Returns a timer id you can use to
cancel it.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `seconds` | number | ✅ | Delay in seconds (fractions allowed, e.g. `0.5`). |
| `callback` | function | ✅ | Called once after the delay. Takes no args. |

Returns: a timer id (number).

```lua
-- one-shot, 5 seconds
xenon.setTimeout(5, function()
    xenon.toast("5 seconds passed")
end)

-- repeating timer via recursion
local function every_minute()
    xenon.sendMessage("tick", 3284201935)
    xenon.setTimeout(60, every_minute)
end
every_minute()
```

> Timers are owned by the plugin that created them. When the plugin is disabled
> or removed, all its pending timers are cancelled automatically.

---

## `xenon.clearTimeout`

```lua
xenon.clearTimeout(id)
```

Cancels a pending timer created with `setTimeout`.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | number | ✅ | The timer id returned by `setTimeout`. |

Returns: nothing.

```lua
local reminder = xenon.setTimeout(60, function()
    xenon.toast("time's up")
end)

-- cancel it later
xenon.clearTimeout(reminder)
```

> Calling `clearTimeout` with an already-fired or unknown id is a safe no-op.

---

## `xenon.startMessageWatcher`

```lua
xenon.startMessageWatcher(config)
```

Starts a **background poller** that checks a set of chats for new incoming
messages on an interval, and fires your callback when a chat crosses a threshold
of unread incoming messages. Great for "react when a chat gets busy".

`config` is a table:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `key` | string | ✅ | A unique name for this watcher. Lets you stop/replace it. |
| `chats` | table | ✅ | Array of dialog ids (`{123, -456}`). |
| `interval` | number | ❌ | Poll interval in seconds. Minimum `5`. Default `60`. |
| `threshold` | number | ❌ | How many new incoming messages trigger the callback. Min `1`. Default `3`. |
| `callback` | function | ✅ | `function(triggered)` — see below. |

Returns: `true` if started, `nil` on bad config.

The `callback` receives `triggered` — an array of tables:

```lua
{
  { chatId = <number>, new_count = <number> },
  ...
}
```

```lua
local WATCH_CHATS = { 3284201935, -1001234567890 }

xenon.startMessageWatcher({
    key = "busy_detector",
    chats = WATCH_CHATS,
    interval = 30,
    threshold = 5,
    callback = function(triggered)
        for i = 1, #triggered do
            local entry = triggered[i]
            xenon.bulletin("Chat " .. entry.chatId .. " got " .. entry.new_count .. " new msgs")
            xenon.sendMessage("you're busy!", entry.chatId)
        end
    end
})
```

> The watcher establishes a **baseline** (the latest message id per chat) when it
> starts, so it won't fire on messages that were already there. Only **new,
> incoming** (not-from-you) messages count toward `threshold`. State (last-seen
> ids) is persisted in plugin settings under `mw_<key>_ids`, so it survives
> restarts.

---

## `xenon.stopMessageWatcher`

```lua
xenon.stopMessageWatcher(key)
```

Stops the watcher named `key`.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `key` | string | ✅ | The `key` you passed to `startMessageWatcher`. |

Returns: nothing.

```lua
-- restart with a new chat list
local function restart_watcher(chats)
    xenon.stopMessageWatcher("busy_detector")
    if #chats > 0 then
        xenon.startMessageWatcher({
            key = "busy_detector",
            chats = chats,
            interval = 30,
            threshold = 5,
            callback = function(t) /* ... */ end,
        })
    end
end
```

> `startMessageWatcher` with a `key` that already exists silently replaces the
> previous one, but calling `stop` first is clearer.

---

## Lifecycle notes

- **`setTimeout` callbacks** run on the main thread. Don't block.
- **Watcher callbacks** run on the main thread after the poll completes.
- When a plugin is **disabled** or **removed**, all its timers and watchers are
  cleaned up automatically — no manual teardown needed.
- Watchers keep running across `onResume`/`onPause`; they're tied to the plugin
  load lifecycle, not the activity lifecycle.

---

## Choosing between setTimeout and a watcher

| Need | Use |
|------|-----|
| "do X once after a delay" | `setTimeout` |
| "do X every N seconds" | `setTimeout` recursion |
| "react when a chat gets N new messages" | `startMessageWatcher` (server-driven, efficient) |
| "react to every single message as it arrives" | the [`onNewMessage`](./hooks.md#onnewmessage) hook |

For message-driven logic, prefer the **hook** — it's event-driven and
instantaneous. Use a watcher only when you specifically want threshold-based
batch detection across chats.
