# common-module-name-server-call-cached

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `common-module-name-server-call-cached` |
| **Title** | Server call cached common module should end with ServerCallCached suffix |
| **Description** | Check server call common module with caching has "ServerCallCached" suffix |
| **Severity** | `MINOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [469](https://its.1c.ru/db/v8std/content/469/hdoc) |

---

## 🎯 What This Check Does

This check validates that common modules with **Server call** AND **caching** enabled have the appropriate naming suffix:
- Russian: `ВызовСервераПовтИсп`
- English: `ServerCallCached`

### Why This Is Important

- **Performance optimization visibility**: Cached server calls reduce network overhead
- **Combined behavior indication**: Both server call and caching are explicit
- **Standards compliance**: Follows 1C naming conventions (Standard 469)
- **Debugging clarity**: Understand caching behavior from module name

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
<!-- ❌ Noncompliant: Server call cached module without ServerCallCached suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>CatalogServiceServerCall</name>             <!-- ❌ Missing "Cached" - should be "ServerCallCached" -->
  <server>true</server>
  <serverCall>true</serverCall>
  <returnValuesReuse>DuringSession</returnValuesReuse>  <!-- Has caching enabled -->
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>DataServiceServerCall</name>                <!-- ❌ Missing "ПовтИсп" suffix -->
  <server>true</server>
  <serverCall>true</serverCall>
  <returnValuesReuse>DuringRequest</returnValuesReuse>
</mdclass:CommonModule>
```

### Noncompliant Code Example

```
Configuration/
└── CommonModules/
    └── CatalogServiceServerCall/  ❌ Missing "Cached" - should be "ServerCallCached"
        └── Module.bsl
```

**Module Properties:**
- Server call: ✓
- Server: ✓
- Return value reuse: `During session`

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Server call cached module with ServerCallCached suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>CatalogServiceServerCallCached</name>      <!-- ✅ Has "ServerCallCached" suffix -->
  <server>true</server>
  <serverCall>true</serverCall>
  <returnValuesReuse>DuringSession</returnValuesReuse>
</mdclass:CommonModule>

<!-- ✅ Correct: Russian naming with ВызовСервераПовтИсп suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>СервисСправочниковВызовСервераПовтИсп</name>  <!-- ✅ Has "ВызовСервераПовтИсп" suffix -->
  <server>true</server>
  <serverCall>true</serverCall>
  <returnValuesReuse>DuringRequest</returnValuesReuse>
</mdclass:CommonModule>
```

### Correct Module Naming

```
Configuration/
└── CommonModules/
    └── CatalogServiceServerCallCached/  ✅ Has "ServerCallCached" suffix
        └── Module.bsl
```

Or in Russian:

```
Configuration/
└── CommonModules/
    └── СервисСправочниковВызовСервераПовтИсп/  ✅ Has "ВызовСервераПовтИсп" suffix
        └── Module.bsl
```

### Module Settings

| Property | Value |
|----------|-------|
| Name | `CatalogServiceServerCallCached` |
| Server call | ✓ |
| Server | ✓ |
| Return value reuse | `During session` or `During call` |

---

## 📖 ServerCallCached Module Characteristics

### What Makes a ServerCallCached Module

A module is considered "ServerCallCached" when:
- `Server call` = True
- `Server` = True
- `ReturnValuesReuse` = `DuringSession` or `DuringCall`

### How Caching Works

```
First call:
┌─────────────┐         ┌───────────────────────────────┐
│   CLIENT    │  ────►  │  CatalogServiceServerCallCached  │
│             │  HTTP   │         (SERVER)                 │
└─────────────┘         └───────────────────────────────┘
                                    │
                                    ▼
                              [Cache result]

Second call (same parameters):
┌─────────────┐         ┌───────────────────────────────┐
│   CLIENT    │  ────►  │        [Client Cache]         │
│             │  LOCAL  │     No server roundtrip!      │
└─────────────┘         └───────────────────────────────┘
```

### Suffix Options

| Language | Suffix | Example |
|----------|--------|---------|
| English | `ServerCallCached` | `LookupServiceServerCallCached` |
| Russian | `ВызовСервераПовтИсп` | `СервисПоискаВызовСервераПовтИсп` |

---

## ⚡ Performance Benefits

### Why Use ServerCallCached

| Aspect | ServerCall | ServerCallCached |
|--------|------------|------------------|
| First call | Network request | Network request |
| Subsequent calls | Network request | **Local cache** |
| Latency | High (network) | Low (memory) |
| Server load | Every call | First call only |

### Best Use Cases

- Reference data lookups
- Configuration constants
- Rarely changing data
- Dropdown list values

---

## 📋 Typical ServerCallCached Module Content

### Appropriate Usage

```bsl
// ✅ Lookup tables - data rarely changes
Function GetCurrencyList() Export
    Query = New Query;
    Query.Text = "SELECT Code, Description FROM Catalog.Currencies";
    Return Query.Execute().Unload();
EndFunction

// ✅ Configuration constants
Function GetSystemSettings() Export
    Settings = New Structure;
    Settings.Insert("CompanyName", Constants.CompanyName.Get());
    Settings.Insert("DefaultCurrency", Constants.DefaultCurrency.Get());
    Return Settings;
EndFunction

// ✅ Static reference data
Function GetCountryByCode(CountryCode) Export
    Return Catalogs.Countries.FindByCode(CountryCode);
EndFunction
```

### What NOT to Cache

```bsl
// ❌ User-specific data that changes
Function GetUserNotifications() Export
    // Don't cache - notifications change frequently
EndFunction

// ❌ Data that must be current
Function GetCurrentBalance(Account) Export
    // Don't cache - balance needs real-time data
EndFunction
```

---

## 🔧 How to Fix

### Step 1: Verify module properties

Check that module has:
- Server call = True
- Server = True
- Return value reuse = enabled

### Step 2: Rename the module

Ensure suffix includes both `ServerCall` and `Cached`:

**Before:** `CatalogServiceServerCall` (missing Cached)  
**After:** `CatalogServiceServerCallCached`

### Step 3: Update all references

```bsl
// Before
Data = CatalogServiceServerCall.GetCurrencyList();

// After
Data = CatalogServiceServerCallCached.GetCurrencyList();
```

---

## 🔍 Technical Details

### Check Implementation Class

```
com.e1c.v8codestyle.md.commonmodule.check.CommonModuleNameServerCallCached
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/commonmodule/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 469](https://its.1c.ru/db/v8std/content/469/hdoc)
