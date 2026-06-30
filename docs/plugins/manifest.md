# Plugin manifest & settings schema

The manifest is a set of top-level variables that describe your plugin to the
engine. Two of them are required; the rest are optional. This page also
documents the `plugin_settings` schema, which renders a native settings UI.

---

## Manifest variables

Declare these at the **top level** of your `.xplugin` file (not inside a
function). The engine reads them after running your script.

| Variable | Required | Type | Description |
|----------|----------|------|-------------|
| `plugin_id` | ✅ **yes** | string | Unique stable id, `snake_case`. **A plugin without this is rejected at install.** |
| `plugin_name` | string | Display name (shown in the plugins list). Falls back to the file name. |
| `plugin_description` | string | One-line description shown under the name. |
| `plugin_settings` | table | Settings schema — renders a native UI. See below. |
| `plugin_scopes` | table | Security scopes the plugin needs (e.g. `{"MESSAGING"}`). See [security.md](./security.md). |

```lua
plugin_id          = "my_cool_plugin"
plugin_name        = "My Cool Plugin"
plugin_description = "Does something cool"
```

### `plugin_id` — the rules

- **Mandatory.** Installation fails without it.
- **Unique.** Two plugins with the same `plugin_id` can't coexist — installing a
  new one with a taken id replaces the old.
- **Stable.** Don't change it between versions; it's how updates and settings
  persistence are tracked.
- **Format:** `snake_case`, e.g. `message_roulette`, `auto_reply_bot`.

---

## The settings schema — `plugin_settings`

`plugin_settings` is an **array of row definitions**. Each row has a `type` that
determines how it renders and what it stores. The engine builds a settings screen
for your plugin from this table.

```lua
plugin_settings = {
    {type = "header", key = "main",   name = "General"},
    {type = "toggle", key = "enabled", name = "Enabled", default = true},
    {type = "seekbar", key = "delay",  name = "Delay (s)", min = 0, max = 60, step = 5, default = 10},
    {type = "button", key = "test",    name = "Send test", action = function() /*...*/ end},
}
```

Every row type shares two common keys:

| Key | Type | Description |
|-----|------|-------------|
| `type` | string | One of `toggle`, `seekbar`, `text`, `button`, `header`. |
| `key` | string | Stable id for the row (used for settings storage on `toggle`/`seekbar`). |
| `name` | string | Label shown to the user. |

---

### `toggle`

A switch. Stores `"true"` / `"false"`.

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `default` | boolean | ❌ | Initial value. Default `false`. |

```lua
{type = "toggle", key = "loud", name = "Loud mode", default = true}
```

Read it back: `xenon.getSetting("loud", "true") == "true"`.

---

### `seekbar`

A slider for picking a number. Stores the number as a string.

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `min` | number | ✅ | Minimum value. |
| `max` | number | ✅ | Maximum value. |
| `step` | number | ❌ | Step size (must be ≥ 1). Default `1`. |
| `default` | number | ❌ | Initial value (clamped to `[min,max]`). |

```lua
{type = "seekbar", key = "count", name = "How many", min = 1, max = 50, step = 1, default = 10}
```

Read it back: `tonumber(xenon.getSetting("count", "10")) or 10`.

---

### `text`

A **static, non-interactive** label — a dimmed text row used for information
lines, hints, or "empty state" messages. It holds no value the user can change;
it's purely visual. Stores nothing.

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `default` | string | ❌ | Shown as a subtitle under `name` if non-empty. |

```lua
-- a hint line
{type = "text", key = "info", name = "Tip", default = "Add chats via the button above"}

-- an empty-state line (subtitle omitted)
{type = "text", key = "empty", name = "No chats added yet"}
```

> For a tappable action use [`button`](#button). `text` is display-only and
> meant for labels, not interactive controls.

---

### `button`

A tappable row that runs a Lua function.

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `action` | function | ✅ | Called (no args) when the user taps the row. |

```lua
{type = "button", key = "run", name = "Run now", action = function()
    xenon.sendMessage("triggered", 3284201935)
end}
```

> The `action` closure captures the Lua environment at load time, so it can read
> your plugin's locals and call any `xenon.*` function.

---

### `header`

A section divider with a title. Stores nothing.

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| (only `key`/`name`) | | | `name` is the header text. |

```lua
{type = "header", key = "advanced", name = "ADVANCED"}
```

---

## Dynamically rebuilding settings

`plugin_settings` can be rebuilt at runtime — e.g. to show a list of saved chats
that the user added via a picker. Reassign the global and call `refreshSettings`:

```lua
local function rebuild()
    plugin_settings = {
        {type = "toggle", key = "enabled", name = "Enabled", default = true},
        {type = "button", key = "add", name = "+ Add chat", action = function()
            xenon.openChatPicker(function(chatId)
                -- ... save chatId ...
                rebuild()                       -- rebuild table
                xenon.refreshSettings()         -- redraw UI
            end)
        end},
    }
    -- append saved chats here...
end

rebuild()
```

---

## Full reference example

```lua
plugin_id          = "settings_demo"
plugin_name        = "Settings Demo"
plugin_description = "Shows every settings row type"

plugin_settings = {
    {type = "header",  key = "g1",     name = "TOGGLES"},
    {type = "toggle",  key = "enabled", name = "Enabled", default = true},
    {type = "toggle",  key = "verbose", name = "Verbose logs", default = false},

    {type = "header",  key = "g2",     name = "VALUES"},
    {type = "seekbar", key = "delay",  name = "Delay",
     min = 0, max = 60, step = 5, default = 15},
    {type = "seekbar", key = "count",  name = "Count",
     min = 1, max = 100, step = 1, default = 10},

    {type = "header",  key = "g3",     name = "ACTIONS"},
    {type = "button",  key = "test",   name = "Send test message", action = function()
        xenon.sendMessage("test from Settings Demo", 3284201935)
    end},
    {type = "text",    key = "tip",    name = "Tip", default = "Toggles save automatically"},
}

xenon.on("onResume", function()
    if xenon.getSetting("enabled", "true") ~= "true" then return end
    local n = tonumber(xenon.getSetting("count", "10")) or 10
    if xenon.getSetting("verbose", "false") == "true" then
        xenon.toast("running, count=" .. n)
    end
end)
```
