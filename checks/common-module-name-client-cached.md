# common-module-name-client-cached

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `common-module-name-client-cached` |
| **Title** | Client cached common module should end with ClientCached suffix |
| **Description** | Check client common module with caching has "ClientCached" suffix |
| **Severity** | `MINOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [469](https://its.1c.ru/db/v8std/content/469/hdoc) |

---

## 🎯 What This Check Does

This check validates that common modules configured to run on **client only** with **return value caching** have the appropriate naming suffix:
- Russian: `КлиентПовтИсп`
- English: `ClientCached`

### Why This Is Important

- **Combined behavior indication**: Module is client-only AND cached
- **Performance awareness**: Developers know values are cached
- **Standards compliance**: Follows 1C naming conventions (Standard 469)
- **Clear naming**: Both execution context and caching are visible in name

---

## ❌ Error Example

### Error Message

```
Common module should end with {suffix}
```

**Russian:**
```
Common module should be named with {suffix} postfix
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Noncompliant: Client cached module without ClientCached suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>UISettingsClient</name>                     <!-- ❌ Missing "Cached" - should be "ClientCached" -->
  <server>false</server>
  <clientManagedApplication>true</clientManagedApplication>
  <returnValuesReuse>DuringSession</returnValuesReuse>  <!-- Has caching enabled -->
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>FormSettingsClient</name>                   <!-- ❌ Missing "ПовтИсп" suffix -->
  <server>false</server>
  <clientManagedApplication>true</clientManagedApplication>
  <returnValuesReuse>DuringRequest</returnValuesReuse>
</mdclass:CommonModule>
```

### Noncompliant Code Example

```
Configuration/
└── CommonModules/
    └── UISettingsClient/  ❌ Missing "Cached" - should be "ClientCached"
        └── Module.bsl
```

**Module Properties:**
- Client (managed application): ✓
- Server: ✗
- Return value reuse: `During session`

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Client cached module with ClientCached suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>UISettingsClientCached</name>              <!-- ✅ Has "ClientCached" suffix -->
  <server>false</server>
  <clientManagedApplication>true</clientManagedApplication>
  <returnValuesReuse>DuringSession</returnValuesReuse>
</mdclass:CommonModule>

<!-- ✅ Correct: Russian naming with КлиентПовтИсп suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>НастройкиИнтерфейсаКлиентПовтИсп</name>  <!-- ✅ Has "КлиентПовтИсп" suffix -->
  <server>false</server>
  <clientManagedApplication>true</clientManagedApplication>
  <returnValuesReuse>DuringRequest</returnValuesReuse>
</mdclass:CommonModule>
```

### Correct Module Naming

```
Configuration/
└── CommonModules/
    └── UISettingsClientCached/  ✅ Has "ClientCached" suffix
        └── Module.bsl
```

Or in Russian:

```
Configuration/
└── CommonModules/
    └── НастройкиИнтерфейсаКлиентПовтИсп/  ✅ Has "КлиентПовтИсп" suffix
        └── Module.bsl
```

### Module Settings

| Property | Value |
|----------|-------|
| Name | `UISettingsClientCached` |
| Client (managed application) | ✓ |
| Server | ✗ |
| Return value reuse | `During session` or `During call` |

---

## 📖 ClientCached Module Characteristics

### What Makes a ClientCached Module

A module is considered "ClientCached" when:
- `Client (managed application)` = True
- `Server` = False
- `ReturnValuesReuse` = `DuringSession` or `DuringCall`

### Suffix Order

| Components | Combined Suffix | Example |
|------------|-----------------|---------|
| Client + Cached | `ClientCached` | `SettingsClientCached` |
| Client + Cached (RU) | `КлиентПовтИсп` | `НастройкиКлиентПовтИсп` |

---

## 📋 Typical ClientCached Module Content

### Good Examples

```bsl
// ✅ Client-side cached settings
Function GetUserInterfaceSettings() Export
    // Value is cached for session
    Settings = New Structure;
    Settings.Insert("Theme", "Light");
    Settings.Insert("FontSize", 12);
    Return Settings;
EndFunction

// ✅ Cached form constants
Function GetFormConstants() Export
    // Called multiple times, returned from cache
    Return New Structure("MaxRows,PageSize", 100, 20);
EndFunction
```

---

## 🔧 How to Fix

### Step 1: Verify module properties

Check that module has:
- Client = True
- Server = False
- Return value reuse = enabled

### Step 2: Rename the module

Ensure suffix includes both `Client` and `Cached`:

**Before:** `UISettingsClient` (missing Cached)  
**After:** `UISettingsClientCached`

### Step 3: Update all references

```bsl
// Before
Settings = UISettingsClient.GetUserSettings();

// After
Settings = UISettingsClientCached.GetUserSettings();
```

---

## 🔍 Technical Details

### Check Implementation Class

```
com.e1c.v8codestyle.md.commonmodule.check.CommonModuleNameClientCached
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/commonmodule/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 469](https://its.1c.ru/db/v8std/content/469/hdoc)
