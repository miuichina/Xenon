# Settings & storage

Plugins persist state between launches with a simple key/value store. There's
also a settings schema (`plugin_settings`) that renders a native UI for the user.

---

## The store

Settings are **key → string** pairs, scoped per-plugin. Each plugin's keys live
under a namespace (`<fileName>_<key>`), so two plugins named `foo` and `bar` can
both have a key called `count` without colliding.

> **Critical:** values are always **strings**. Booleans become `"true"`/`"false"`,
> numbers become `"42"`. You must convert on read.

---

## `xenon.getSetting`

```lua
xenon.getSetting(key [, default])
```

Reads a stored value. Returns `default` if the key was never set.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `key` | string | ✅ | Setting name. |
| `default` | any | ❌ | Returned if the key is unset. Coerced to string. |

Returns: the stored **string**, or `default` (as a string), or `nil`.

```lua
-- booleans
local loud = xenon.getSetting("loud", "true") == "true"

-- numbers
local n = tonumber(xenon.getSetting("count", "0")) or 0

-- strings
local name = xenon.getSetting("name", "anonymous")
```

A robust typed helper:

```lua
local function get_setting(key, default)
    local val = xenon.getSetting(key, tostring(default))
    if type(default) == "boolean" then
        return val == "true"
    elseif type(default) == "number" then
        return tonumber(val) or default
    end
    return val
end

-- usage
if get_setting("loud", true)  then xenon.toast("LOUD")   end
local delay = get_setting("delay", 5)
```

---

## `xenon.setSetting`

```lua
xenon.setSetting(key, value)
```

Stores a value. `value` is coerced to a string.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `key` | string | ✅ | Setting name. |
| `value` | any | ✅ | Value to store (converted with `tostring`). |

Returns: nothing.

```lua
xenon.setSetting("count", tostring(n + 1))
xenon.setSetting("enabled", "true")
xenon.setSetting("last_chat", tostring(chatId))

-- a list, serialized manually
xenon.setSetting("ids", table.concat({1, 2, 3}, ","))
```

> There's no structured/array type. For lists, serialize with
> `table.concat` and parse with `string.gmatch`.

---

## `xenon.refreshSettings`

```lua
xenon.refreshSettings()
```

Tells the app to **rebuild the plugin's settings screen**. Call this whenever you
change `plugin_settings` at runtime (e.g. after adding/removing a saved chat).

Returns: nothing.

```lua
local function add_chat(chatId)
    -- ... append to stored list ...
    rebuild_settings_table()   -- rebuilds the local plugin_settings variable
    xenon.refreshSettings()    -- tells the UI to redraw
end
```

> `refreshSettings` only affects the **currently open** plugin settings screen.
> If the screen isn't open, the next time it opens it'll be fresh anyway.

---

## The settings UI — `plugin_settings`

To give the user a native settings screen, define a top-level `plugin_settings`
table. The engine renders each entry as a row. See [manifest.md](./manifest.md)
for the full schema; the supported row types are:

| `type` | Renders as | Stores |
|--------|-----------|--------|
| `toggle` | A switch | `"true"` / `"false"` |
| `seekbar` | A slider | a number (as string) |
| `text` | Read-only text / label | — |
| `button` | A tappable row | runs `action` |
| `header` | A section header | — |

The UI writes back to the store using the entry's `key`, so after the user
changes a toggle, `xenon.getSetting("that_key")` returns the new value.

---

## Complete example

```lua
plugin_id = "greeter"
plugin_name = "Greeter"
plugin_description = "Configurable greeting"

plugin_settings = {
    {type = "toggle", key = "enabled", name = "Enabled", default = true},
    {type = "seekbar", key = "count", name = "Send count",
     min = 1, max = 10, step = 1, default = 3},
    {type = "button", key = "test", name = "Send test now",
     action = function()
         xenon.sendMessage("test", 3284201935)
     end},
}

xenon.on("onResume", function()
    if xenon.getSetting("enabled", "true") ~= "true" then return end
    local n = tonumber(xenon.getSetting("count", "3")) or 3
    for i = 1, n do
        xenon.sendMessage("hi " .. i, 3284201935)
    end
end)
```
