# common-module-name-global-client

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `common-module-name-global-client` |
| **Title** | Global client common module should end with GlobalClient suffix |
| **Description** | Check global client common module name has "GlobalClient" suffix |
| **Severity** | `CRITICAL` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [469](https://its.1c.ru/db/v8std/content/469/hdoc) |

---

## 🎯 What This Check Does

This check validates that common modules with **Global** attribute enabled (client context) have the appropriate naming suffix:
- Russian: `ГлобальныйКлиент`
- English: `GlobalClient`

### Why This Is Important

- **Context + scope clarity**: Module is both global AND client-side
- **Namespace awareness**: Methods available without module prefix on client
- **Standards compliance**: Follows 1C naming conventions (Standard 469)
- **Code organization**: Distinguishes from server global modules

---

## ❌ Error Example

### Error Message

```
Global client common module name should end with "{suffix}" suffix
```

**Russian:**
```
Global client common module should end with "{suffix}" suffix
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Noncompliant: Global client module without GlobalClient suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>ClientHelpers</name>                        <!-- ❌ Missing "GlobalClient" suffix -->
  <server>false</server>
  <global>true</global>                             <!-- Global enabled -->
  <clientManagedApplication>true</clientManagedApplication>  <!-- Client enabled -->
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>UIHandlers</name>                           <!-- ❌ Missing "ГлобальныйКлиент" suffix -->
  <server>false</server>
  <global>true</global>
  <clientManagedApplication>true</clientManagedApplication>
</mdclass:CommonModule>
```

### Noncompliant Code Example

```
Configuration/
└── CommonModules/
    └── ClientHelpers/  ❌ Missing "GlobalClient" suffix
        └── Module.bsl
```

**Module Properties:**
- Global: ✓
- Client (managed application): ✓
- Server: ✗

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Global client module with GlobalClient suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>ClientHelpersGlobalClient</name>           <!-- ✅ Has "GlobalClient" suffix -->
  <server>false</server>
  <global>true</global>
  <clientManagedApplication>true</clientManagedApplication>
</mdclass:CommonModule>

<!-- ✅ Correct: Russian naming with ГлобальныйКлиент suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>КлиентскиеПомощникиГлобальныйКлиент</name> <!-- ✅ Has "ГлобальныйКлиент" suffix -->
  <server>false</server>
  <global>true</global>
  <clientManagedApplication>true</clientManagedApplication>
</mdclass:CommonModule>
```

### Correct Module Naming

```
Configuration/
└── CommonModules/
    └── ClientHelpersGlobalClient/  ✅ Has "GlobalClient" suffix
        └── Module.bsl
```

Or in Russian:

```
Configuration/
└── CommonModules/
    └── КлиентскиеПомощникиГлобальныйКлиент/  ✅ Has "ГлобальныйКлиент" suffix
        └── Module.bsl
```

### Module Settings

| Property | Value |
|----------|-------|
| Name | `ClientHelpersGlobalClient` |
| Global | ✓ |
| Client (managed application) | ✓ |
| Server | ✗ |

---

## 📖 GlobalClient Module Characteristics

### What Makes a GlobalClient Module

A module is considered "GlobalClient" when:
- `Global` = True
- `Client (managed application)` = True
- `Server` = False

### Effects of Global Attribute on Client

```bsl
// In a GlobalClient module:
Procedure ShowQuickNotification(Text) Export
    ShowUserNotification(Text);
EndProcedure

// Can be called WITHOUT module name prefix from client code:
ShowQuickNotification("Done!");  // Works in client context

// Can also be called with prefix:
ClientHelpersGlobalClient.ShowQuickNotification("Done!");
```

### Suffix Options

| Language | Suffix | Example |
|----------|--------|---------|
| English | `GlobalClient` | `UIHelpersGlobalClient` |
| Russian | `ГлобальныйКлиент` | `РаботаСИнтерфейсомГлобальныйКлиент` |

---

## 📋 Comparison: Client Module Types

| Module Type | Suffix | Global | Client | Server |
|-------------|--------|--------|--------|--------|
| Client only | `Client` | ✗ | ✓ | ✗ |
| Client global | `GlobalClient` | ✓ | ✓ | ✗ |
| Client + Server | `ClientServer` | ✗ | ✓ | ✓ |

---

## 📋 Typical GlobalClient Module Content

### Appropriate Usage

```bsl
// ✅ Frequently used UI helpers
Procedure ShowError(ErrorText) Export
    ShowMessageBox(, ErrorText);
EndProcedure

// ✅ Quick notifications
Procedure Notify(Title, Text = "") Export
    ShowUserNotification(Title, , Text);
EndProcedure

// ✅ Form utilities used everywhere
Function GetFormOwner(Form) Export
    Return Form.FormOwner;
EndFunction
```

---

## 🔧 How to Fix

### Step 1: Verify module properties

Check that module has:
- Global = True
- Client = True
- Server = False

### Step 2: Rename the module

Add the `GlobalClient` suffix:

**Before:** `ClientHelpers`  
**After:** `ClientHelpersGlobalClient`

### Step 3: Update all references

```bsl
// Before (if used with prefix)
ClientHelpers.ShowNotification(Text);

// After
ClientHelpersGlobalClient.ShowNotification(Text);

// Or use global call (works because module is Global)
ShowNotification(Text);
```

---

## 🔍 Technical Details

### Check Implementation Class

```
com.e1c.v8codestyle.md.commonmodule.check.CommonModuleNameGlobalClient
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/commonmodule/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 469](https://its.1c.ru/db/v8std/content/469/hdoc)
