# Bindings and validation

A `Binding<T>` is how a setting reaches your data. It is the only connection between the
framework and whatever you actually store.

## Ways to bind

### A mutable property

```kotlin
bind(config::enabled)
```

The common case. Property references are resolved by the compiler, not by runtime
reflection, so this allocates nothing per read and is safe outside hot paths.

### A getter and setter pair

```kotlin
bind(
    get = { config.readEnabled() },
    set = { config.writeEnabled(it) },
    debugName = "enabled",
)
```

For values behind methods rather than a property. The framework cannot observe an
arbitrary getter, so this creates an observable mirror seeded from `get`. Writes go to
both `set` and the mirror.

**If the underlying value can also change behind the framework's back**, the mirror will
be stale. Call `refresh()` when you know it changed:

```kotlin
val binding = bind(get = ..., set = ...)
// later, after something else mutated the source:
binding.refresh()
```

### Observable state

```kotlin
val enabled = mutableStateOf(true, "enabled")
enabled.asBinding()          // read-write
someState.asReadOnlyBinding() // read-only; the control renders but refuses input
```

Best when you already hold state, because there is no mirror to keep in sync.

### Transforming

```kotlin
bind(config::durationTicks).map(
    to = { it / 20f },          // stored ticks -> displayed seconds
    from = { (it * 20f).toInt() },
)
```

For when what you store and what you show differ — ticks on disk, seconds on screen.

---

## Failure behaviour

A binding that throws produces a `BindingException` naming the binding, rather than
failing silently. In a development environment this surfaces loudly; in production the
control degrades to a visible placeholder instead of taking down the screen.

This is the same principle as an unregistered setting type rendering as
`MissingComponentNode`: a visible wrong thing is better than an invisible one. It is also
why that placeholder is worth testing for — see [failure cases](failure-cases.md).

---

## Validation

Validators run on write. A rejected value does not reach your data.

```kotlin
textField(id, "Username", bind(config::username), validator = Validators.notBlank())

slider(id, "Count", bind(config::count), 0..100, validator = Validators.intRange(1..99))
```

### Built-in validators

| Validator | Rejects |
| --- | --- |
| `Validators.required()` | null |
| `Validators.notBlank()` | empty or whitespace-only strings |
| `Validators.intRange(1..99)` | out-of-range integers |
| `Validators.floatRange(0f..1f)` | out-of-range decimals |
| `Validators.length(2..32)` | strings outside a length range |
| `Validators.matches(regex)` | strings not matching |
| `Validators.validRegex()` | strings that are not valid regexes |
| `Validators.oneOf(allowed)` | values outside a set |
| `Validators.satisfies(message) { }` | anything your predicate refuses |
| `Validators.crossField(other, otherValue, message) { }` | values inconsistent with another setting |

### Writing your own

```kotlin
val noReservedNames = Validator<String> { field, value ->
    if (value.lowercase() in RESERVED) {
        ValidationResult.error(field, "'$value' is reserved")
    } else {
        ValidationResult.valid()
    }
}
```

A result carries issues rather than a boolean, so a control can show *why* a value was
refused. Issues have a severity: an error blocks the write, a warning does not. Check it
with `result.isValid`, and read `result.primaryIssue` for the message to show.

```kotlin
ValidationResult.warning(field, "This is unusually large")   // allowed, but flagged
```

### Cross-field validation

```kotlin
validator = Validators.crossField<Int, Int>(
    other = maximumSetting.id,
    otherValue = { maximumSetting.value },
    message = "Minimum cannot exceed maximum",
) { value, maximum -> value <= maximum }
```

The issue records `other` in its related fields, so a screen can highlight both sides of
the problem rather than only the one that was typed into.

### Asynchronous validation

`AsyncValidator` exists for checks that need I/O — verifying a name against a server, say.
**Nothing currently uses it.** The interface is stable but unexercised, so treat it as a
sketch rather than a supported path until something proves it out.

---

## Bypassing validation

`setting.setUnchecked(value)` writes without validating. It exists for the framework's own
use — loading a file, applying an undo — where the value has already been through
validation once and re-running it would reject data that is legitimately on disk.

Prefer `setting.set(value)` in your own code — it returns a `ValidationResult`, so you
can tell whether the write actually took. If you find yourself reaching for
`setUnchecked`, the question worth asking is why the value is invalid.

---

## Undo

Every write through a binding can be recorded:

```kotlin
undoStack.pushSettingChange(setting, from = oldValue, to = newValue)
```

Consecutive writes to the same setting merge into one entry, so dragging a slider produces
one undoable change rather than one per pixel. For gestures spanning several settings,
open an explicit gesture:

```kotlin
undoStack.beginGesture("Move HUD")
// …many writes…
undoStack.endGesture()
```

`abortGesture()` ends it *and discards* what it accumulated — for a gesture the user
cancelled, where the caller has already restored the starting state.
