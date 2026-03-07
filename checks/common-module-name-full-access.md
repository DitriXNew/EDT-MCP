# common-module-name-full-access

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `common-module-name-full-access` |
| **Title** | Privileged common module should end with FullAccess suffix |
| **Description** | Check privileged common module name has "FullAccess" suffix |
| **Severity** | `CRITICAL` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [469](https://its.1c.ru/db/v8std/content/469/hdoc) |

---

## 🎯 What This Check Does

This check validates that common modules with **Privileged** mode enabled have the appropriate naming suffix:
- Russian: `ПолныеПрава`
- English: `FullAccess`

### Why This Is Important

- **Security awareness**: Privileged mode bypasses access rights
- **Audit visibility**: Easy to find all privileged code locations
- **Code review**: Reviewers can quickly identify security-sensitive modules
- **Standards compliance**: Follows 1C naming conventions (Standard 469)

---

## ❌ Error Example

### Error Message

```
Privileged common module name should end with "{suffix}" suffix
```

**Russian:**
```
Privileged common module should end with "{suffix}" suffix
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Noncompliant: Privileged module without FullAccess suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>AdminFunctions</name>                       <!-- ❌ Missing "FullAccess" suffix -->
  <server>true</server>
  <privileged>true</privileged>                     <!-- Privileged mode enabled -->
</mdclass:CommonModule>

<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>SystemOperations</name>                     <!-- ❌ Missing "ПолныеПрава" suffix -->
  <server>true</server>
  <privileged>true</privileged>
</mdclass:CommonModule>
```

### Noncompliant Code Example

```
Configuration/
└── CommonModules/
    └── AdminFunctions/  ❌ Missing "FullAccess" suffix
        └── Module.bsl
```

**Module Properties:**
- Privileged: ✓

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Privileged module with FullAccess suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>AdminFunctionsFullAccess</name>            <!-- ✅ Has "FullAccess" suffix -->
  <server>true</server>
  <privileged>true</privileged>
</mdclass:CommonModule>

<!-- ✅ Correct: Russian naming with ПолныеПрава suffix -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>АдминистративныеФункцииПолныеПрава</name>  <!-- ✅ Has "ПолныеПрава" suffix -->
  <server>true</server>
  <privileged>true</privileged>
</mdclass:CommonModule>
```

### Correct Module Naming

```
Configuration/
└── CommonModules/
    └── AdminFunctionsFullAccess/  ✅ Has "FullAccess" suffix
        └── Module.bsl
```

Or in Russian:

```
Configuration/
└── CommonModules/
    └── АдминистративныеФункцииПолныеПрава/  ✅ Has "ПолныеПрава" suffix
        └── Module.bsl
```

### Module Settings

| Property | Value |
|----------|-------|
| Name | `AdminFunctionsFullAccess` |
| Privileged | ✓ |
| Server | ✓ |

---

## ⚠️ Security Considerations

### What Privileged Mode Does

- **Bypasses ALL access rights checks**
- Code runs with full database access
- User role restrictions are ignored
- Audit trail should track privileged operations

### When to Use Privileged Modules

| Use Case | Example |
|----------|---------|
| System maintenance | Clearing expired sessions |
| Data migration | Updating all records |
| Administrative functions | User management |
| Background jobs | Scheduled data processing |

### When NOT to Use

- Regular business logic
- User-facing operations
- Operations that should respect user rights

---

## 📖 FullAccess Module Characteristics

### What Makes a FullAccess Module

A module is considered "FullAccess" when:
- `Privileged` = True

### Suffix Options

| Language | Suffix | Example |
|----------|--------|---------|
| English | `FullAccess` | `DataMigrationFullAccess` |
| Russian | `ПолныеПрава` | `МиграцияДанныхПолныеПрава` |

---

## 📋 Typical FullAccess Module Content

### Appropriate Usage

```bsl
// ✅ Administrative operations requiring full access
Procedure DeleteExpiredSessions() Export
    // Deletes sessions regardless of user permissions
    Query = New Query;
    Query.Text = "SELECT Ref FROM InformationRegister.Sessions WHERE ExpirationDate < &Now";
    Query.SetParameter("Now", CurrentSessionDate());
    
    Selection = Query.Execute().Select();
    While Selection.Next() Do
        RecordManager = InformationRegisters.Sessions.CreateRecordManager();
        RecordManager.Ref = Selection.Ref;
        RecordManager.Delete();
    EndDo;
EndProcedure

// ✅ System data update
Procedure UpdateAllDocumentNumbers() Export
    // Updates numbers bypassing document access rights
    // Used during migration or system maintenance
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Verify module properties

Check that module has:
- Privileged = True

### Step 2: Rename the module

Add the `FullAccess` suffix:

**Before:** `AdminFunctions`  
**After:** `AdminFunctionsFullAccess`

### Step 3: Update all references

```bsl
// Before
AdminFunctions.DeleteExpiredSessions();

// After
AdminFunctionsFullAccess.DeleteExpiredSessions();
```

### Step 4: Review privileged usage

Ensure privileged mode is actually needed:
- Can the operation work with normal rights?
- Is there a security risk?

---

## 🔍 Technical Details

### Check Implementation Class

```
com.e1c.v8codestyle.md.commonmodule.check.CommonModuleNameFullAccess
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/commonmodule/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 469](https://its.1c.ru/db/v8std/content/469/hdoc)
