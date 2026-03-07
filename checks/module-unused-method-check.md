# module-unused-method-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `module-unused-method-check` |
| **Title** | Unused method check |
| **Description** | Checks for procedures and functions that are never called |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **procedures and functions** that are defined but **never called** from anywhere in the module. Unused methods are dead code that should be removed.

### Why This Is Important

- **Dead code**: Unused methods increase code size unnecessarily
- **Maintenance burden**: More code to review and maintain
- **Confusion**: Developers may think the method is important
- **Technical debt**: Accumulates over time

---

## ❌ Error Example

### Error Message

```
Unused method "{MethodName}"
```

### Noncompliant Code Example

```bsl
// ❌ Method is never called
Procedure OldProcessing() // ❌ Unused
    // Old code that's no longer needed
EndProcedure

Function LegacyCalculation() // ❌ Unused
    // Legacy calculation no longer used
    Return 0;
EndFunction

// This is the only method that gets called
Procedure MainProcess() Export
    NewProcessing();
EndProcedure

Procedure NewProcessing()
    // Current implementation
EndProcedure
```

---

## ✅ Compliant Solution

### Code with Only Used Methods

```bsl
// ✅ All methods are used
Procedure MainProcess() Export
    PrepareData();
    Calculate();
    SaveResults();
EndProcedure

Procedure PrepareData() // ✅ Called from MainProcess
    // Preparation logic
EndProcedure

Function Calculate() // ✅ Called from MainProcess
    Return 100;
EndFunction

Procedure SaveResults() // ✅ Called from MainProcess
    // Save logic
EndProcedure
```

---

## 📋 When Methods Appear Unused

### 1. Event Handlers

Event handlers are called by the platform, not by your code:

```bsl
// ⚠️ May appear unused but is platform event handler
Procedure BeforeWrite(Cancel)
    ValidateData(Cancel);
EndProcedure
```

### 2. Export Methods

Export methods may be called from other modules:

```bsl
// ⚠️ May be called from external code
Procedure PublicAPI() Export
    // Called from other modules
EndProcedure
```

### 3. Callback Methods

Used as NotifyDescription callbacks:

```bsl
// ⚠️ Called as callback
Procedure ProcessResult(Result, AdditionalParameters) Export
    // Called via NotifyDescription
EndProcedure
```

### 4. Dynamic Calls

Methods called via Execute() or reflection:

```bsl
// ⚠️ Called dynamically
Procedure Handler_DocumentType()
    // Called via: Execute("Handler_" + Type + "()")
EndProcedure
```

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `excludeMethodNamePattern` | `""` | Regex pattern for method names to exclude |

### Configuration Examples

```
# Exclude event handlers
excludeMethodNamePattern = On.*|Before.*|After.*|.*Command

# Exclude callback methods
excludeMethodNamePattern = .*Callback|.*Handler|.*Notification
```

---

## 🔧 How to Fix

### Option 1: Remove the Unused Method

```bsl
// Before
Procedure UsedMethod()
    DoWork();
EndProcedure

Procedure UnusedMethod() // Never called
    OldWork();
EndProcedure

// After
Procedure UsedMethod()
    DoWork();
EndProcedure
// UnusedMethod removed
```

### Option 2: Add Export If Needed Externally

```bsl
// If method should be called from other modules
Procedure ShouldBePublic() Export // Add Export
    // Implementation
EndProcedure
```

### Option 3: Add to Exclusion Pattern

If the method is used but not detectable (callbacks, dynamic calls):

```
excludeMethodNamePattern = ProcessResult.*|Handler_.*
```

---

## 📖 Investigation Steps

### Step 1: Search for Usages

Use IDE's "Find References" to check if method is used anywhere.

### Step 2: Check for Dynamic Calls

Search for:
- `Execute(`
- `Eval(`
- Method name as string

### Step 3: Check Event Subscriptions

Review if method is:
- Form event handler
- Object event handler
- Subscription handler

### Step 4: Check NotifyDescription Usage

```bsl
// Method may be referenced here
Description = New NotifyDescription("MethodName", ThisObject);
```

### Step 5: Check External Calls

If method is Export, check other modules.

---

## 📋 Categories of Unused Methods

### 1. Truly Dead Code

```bsl
// Should be deleted
Procedure ObsoleteFeature()
    // Feature was removed
EndProcedure
```

### 2. Work In Progress

```bsl
// Incomplete implementation
Procedure FutureFeature()
    // TODO: Implement
EndProcedure
```

### 3. Defensive Code

```bsl
// Rarely used fallback
Procedure ErrorRecovery()
    // Only called in edge cases
EndProcedure
```

### 4. Test Helpers

```bsl
// Only used in tests
Procedure ResetState() Export
    // For testing
EndProcedure
```

---

## ⚠️ False Positives

The check may incorrectly flag:

1. **Event handlers** - Called by platform
2. **Export methods** - Called from other modules
3. **Callback methods** - Used with NotifyDescription
4. **Dynamic calls** - Via Execute()
5. **Interface implementations** - Required by contract

Use exclusion patterns for these cases.

---

## 🔍 Technical Details

### What Is Checked

1. All procedure and function declarations
2. Method call analysis within module
3. Identifies never-called methods

### Limitations

- Cannot detect external calls to Export methods
- Cannot detect dynamic/reflection calls
- Cannot detect platform event subscriptions

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ModuleUnusedMethodCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Module Empty Method Check](module-empty-method-check.md)
- [Module Unused Local Variable Check](module-unused-local-variable-check.md)
