# common-module-name-server-call

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `common-module-name-server-call` |
| **Title** | Server call common module should end with ServerCall suffix |
| **Description** | Check server call common module name has "ServerCall" suffix |
| **Severity** | `CRITICAL` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [469](https://its.1c.ru/db/v8std/content/469/hdoc) |

---

## 🎯 What This Check Does

This check validates that common modules with **Server call** attribute enabled have the appropriate naming suffix:
- Russian: `ВызовСервера`
- English: `ServerCall`

### Why This Is Important

- **Performance awareness**: Server calls have network overhead
- **Architecture clarity**: Clear separation of client/server boundary
- **Optimization opportunities**: Easy to find all server call points
- **Standards compliance**: Follows 1C naming conventions (Standard 469)

---

## ❌ Error Example

### Error Message

```
Server call common module name should end with "{suffix}" suffix
```

**Russian:**
```
Общий модуль вызова сервера должен оканчиваться на суффикс "{suffix}"
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Noncompliant: Server call module without ServerCall suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>DataService</name>                          <!-- ❌ Missing "ServerCall" suffix -->
  <server>true</server>
  <serverCall>true</serverCall>                     <!-- Has server call enabled -->
  <clientManagedApplication>false</clientManagedApplication>
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>CommonFunctions</name>                      <!-- ❌ Missing "ServerCall" suffix -->
  <server>true</server>
  <serverCall>true</serverCall>
</mdclass:CommonModule>
```

### Noncompliant Code Example

```
Configuration/
└── CommonModules/
    └── DataService/  ❌ Missing "ServerCall" suffix
        └── Module.bsl
```

**Module Properties:**
- Server call: ✓
- Server: ✓

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Server call module with ServerCall suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>DataServiceServerCall</name>               <!-- ✅ Has "ServerCall" suffix -->
  <server>true</server>
  <serverCall>true</serverCall>
  <clientManagedApplication>false</clientManagedApplication>
</mdclass:CommonModule>

<!-- ✅ Correct: Russian naming with ВызовСервера suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>СервисДанныхВызовСервера</name>            <!-- ✅ Has "ВызовСервера" suffix -->
  <server>true</server>
  <serverCall>true</serverCall>
</mdclass:CommonModule>
```

### Correct Module Naming

```
Configuration/
└── CommonModules/
    └── DataServiceServerCall/  ✅ Has "ServerCall" suffix
        └── Module.bsl
```

Or in Russian:

```
Configuration/
└── CommonModules/
    └── СервисДанныхВызовСервера/  ✅ Has "ВызовСервера" suffix
        └── Module.bsl
```

### Module Settings

| Property | Value |
|----------|-------|
| Name | `DataServiceServerCall` |
| Server call | ✓ |
| Server | ✓ |
| Client (managed application) | ✗ |

---

## 📖 ServerCall Module Characteristics

### What Makes a ServerCall Module

A module is considered "ServerCall" when:
- `Server call` = True
- `Server` = True

### How ServerCall Works

```
┌─────────────┐         ┌─────────────────────────┐
│   CLIENT    │  ────►  │  DataServiceServerCall  │
│   (Form)    │  HTTP   │       (SERVER)          │
└─────────────┘         └─────────────────────────┘

// Client code
Data = DataServiceServerCall.GetDocumentData(DocumentRef);

// The call crosses the client-server boundary
// Network request is made
// Results are serialized and returned
```

### Suffix Options

| Language | Suffix | Example |
|----------|--------|---------|
| English | `ServerCall` | `DocumentsServiceServerCall` |
| Russian | `ВызовСервера` | `СервисДокументовВызовСервера` |

---

## ⚠️ Performance Considerations

### Server Call Overhead

Each server call involves:
- Network request (latency)
- Parameter serialization
- Result serialization
- Authentication check

### Best Practices

```bsl
// ❌ Bad: Multiple server calls
For Each Item In Items Do
    ItemData = DataServiceServerCall.GetItemData(Item);  // N calls!
    ProcessItem(ItemData);
EndDo;

// ✅ Good: Single server call
AllItemsData = DataServiceServerCall.GetAllItemsData(Items);  // 1 call
For Each ItemData In AllItemsData Do
    ProcessItem(ItemData);
EndDo;
```

---

## 📋 Typical ServerCall Module Content

### Appropriate Usage

```bsl
// ✅ Data retrieval from server
Function GetDocumentData(DocumentRef) Export
    If Not ValueIsFilled(DocumentRef) Then
        Return Undefined;
    EndIf;
    
    Return DocumentRef.GetObject();
EndFunction

// ✅ Server-side calculations
Function CalculateDocumentTotals(DocumentRef) Export
    Query = New Query;
    Query.Text = "SELECT SUM(Amount) AS Total FROM Document.Invoice.Items WHERE Ref = &Ref";
    Query.SetParameter("Ref", DocumentRef);
    
    Result = Query.Execute().Unload();
    Return Result[0].Total;
EndFunction

// ✅ Database operations
Procedure SaveUserSettings(SettingsKey, SettingsValue) Export
    CommonSettingsStorage.Save(SettingsKey, , SettingsValue);
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Verify module properties

Check that module has:
- Server call = True
- Server = True

### Step 2: Rename the module

Add the `ServerCall` suffix:

**Before:** `DataService`  
**After:** `DataServiceServerCall`

### Step 3: Update all references

```bsl
// Before
Data = DataService.GetDocumentData(Ref);

// After
Data = DataServiceServerCall.GetDocumentData(Ref);
```

---

## 🔍 Technical Details

### Check Implementation Class

```
com.e1c.v8codestyle.md.commonmodule.check.CommonModuleNameServerCall
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/commonmodule/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 469](https://its.1c.ru/db/v8std/content/469/hdoc)
