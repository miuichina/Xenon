# Safe Mode

The plugin engine has a crash-safety net. If the app crashes on a launch where
plugins were running, the **next launch** automatically disables plugins and
shows a "Crashed!" sheet — so a broken plugin can never lock the user out of
their client.

This is automatic. Plugin authors don't need to do anything to enable it, but
should understand how it behaves so their plugins aren't wrongly blamed.

---

## How it works

1. **On every crash** — of any thread, plugin-related or not — a global
   `UncaughtExceptionHandler` records a stack trace to
   `plugin_crash.txt` in the app's internal storage, and sets a crash flag.
2. **On the next launch** — when the UI is ready, the engine checks the flag. If
   it's set:
   - Plugins are **force-disabled** (`pluginsEnabled = false`), and the engine
     unloads everything.
   - A **BottomSheet** appears:

     ```
     ┌─────────────────────────────┐
     │  Crashed!                   │
     │                             │
     │  The client crashed on the  │
     │  previous launch. Plugins   │
     │  have been disabled to keep │
     │  things stable...           │
     │                             │
     │  Crash time: 2026-06-27 …   │
     │                             │
     │  ─────────────────────────  │
     │       Open plugins          │
     │  ─────────────────────────  │
     │      Copy crash log         │
     │  ─────────────────────────  │
     │          Close              │
     └─────────────────────────────┘
     ```
   - The crash flag is cleared, so the sheet shows only once per crash.

3. The user can then **re-enable plugins** (Settings → Plugins) once they've
   removed the offending one.

---

## The crash log

**Location:** `<app internal storage>/files/plugin_crash.txt`

**Contents:**

```
Xenon plugin crash report
Time: 2026-06-27 14:32:01
Thread: main
App: zxc.iconic.xenon 11.x.x
Android: 14 (Pixel 8)
Plugins enabled: true

--- Stack trace ---
java.lang.NullPointerException: ...
    at zxc.iconic.xenon.plugins.PluginApi.sendMessage(PluginApi.java:612)
    at org.luaj.vm2.LuaValue.call(...)
    ...
```

"Copy crash log" puts this whole file on the clipboard — useful for bug reports.

> The log is **overwritten** on each new crash (it keeps only the most recent
> one). If you need older crashes, use `adb logcat` to capture them live.

---

## For plugin authors — avoiding crashes

Crashes that originate in your plugin fall into a few categories:

| Cause | How to avoid |
|-------|--------------|
| Calling a `xenon.*` function with the wrong types | Check `type(x)` before passing args; the API does some validation but not exhaustively. |
| Indexing a `nil` value from an async callback | Always check `if not result then return end` before `result.field`. |
| Infinite recursion (e.g. a watcher that re-fires itself) | Guard re-entrant calls with a flag. |
| Sending to an invalid `chatId` | Validate the sign (users positive, chats negative). |

Most Lua-level errors (bad indexing, wrong arg count) are **caught by the engine**
and logged — they don't crash the app. Safe Mode mainly catches errors that
escape into native code (null dereferences inside a Java bridge call, stack
overflows, etc.).

### Defensive pattern

```lua
xenon.on("onNewMessage", function(ctx)
    -- guard every external value
    if ctx == nil or ctx.chatId == nil or ctx.msgId == nil then return end

    xenon.getRecentMessages(ctx.chatId, 10, function(all, err)
        if err or not all then return end       -- never assume success
        for i = 1, #all do
            local m = all[i]
            if m and m.text then                -- check before indexing
                -- ...
            end
        end
    end)
end)
```

---

## Testing Safe Mode

To trigger it deliberately (for testing the UI):

1. Install a plugin that forces a crash, e.g.:

   ```lua
   plugin_id = "crash_test"
   plugin_name = "Crash Test"
   plugin_description = "Forces a crash to test Safe Mode"

   xenon.on("onResume", function()
       -- call an internal function in a way that NPEs in native code
       xenon.sendMessage(nil, nil)
   end)
   ```

2. Launch, let it crash.
3. Relaunch — you should see the "Crashed!" sheet, and plugins will be off.

> During development, watch the live log to confirm the crash was captured:
>
> ```
> adb logcat -s XenonPlugin
> ```
