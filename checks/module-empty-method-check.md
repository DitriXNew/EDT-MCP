# module-empty-method-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `module-empty-method-check` |
| **Title** | Empty method check |
| **Description** | Checks for procedures and functions without any executable code |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **empty procedures and functions** that have no executable code. Empty methods are typically dead code or placeholders that were never implemented.

### Why This Is Important

- **Dead code**: Empty methods serve no purpose
- **Maintenance burden**: Creates confusion for developers
- **Code quality**: Indicates incomplete implementation
- **Performance**: Unnecessary method calls

---

## ❌ Error Example

### Error Message

```
Empty method "MethodName"
```

### Noncompliant Code Example

```bsl
// ❌ Completely empty procedure
Procedure DoNothing() Export
EndProcedure

// ❌ Empty function
Function GetValue() Export
EndFunction

// ❌ Only whitespace
Procedure EmptyWithWhitespace() Export
    
    
    
EndProcedure

// ❌ Empty event handler
Procedure ItemOnChange(Item)
EndProcedure

// ❌ Stub not implemented
Procedure ProcessData(Data) Export
    // TODO: Implement this
EndProcedure
```

---

## ✅ Compliant Solution

### Implemented Methods

```bsl
// ✅ Method with actual code
Procedure ProcessData(Data) Export
    If Not ValueIsFilled(Data) Then
        Return;
    EndIf;
    
    // Process the data
    For Each Item In Data Do
        ProcessItem(Item);
    EndDo;
EndProcedure

// ✅ Function returning value
Function GetValue() Export
    Return CurrentSessionDate();
EndFunction

// ✅ Event handler with logic
Procedure ItemOnChange(Item)
    RecalculateTotals();
EndProcedure
```

### Intentionally Empty Methods

```bsl
// ✅ With explanatory comment (if allowed by settings)
Procedure OnOpen(Cancel)
    // Intentionally empty: No special initialization needed
    // Form uses default behavior
EndProcedure

// ✅ Interface stub with explanation
Procedure BeforeWrite(Cancel) Export
    // This is a placeholder for extension points
    // Override in child modules if needed
EndProcedure
```

---

## 📋 When Empty Methods Are Acceptable

### 1. Interface Implementation Stubs

```bsl
// When implementing an interface but not all methods are needed
Procedure OnReadAtServer(CurrentObject)
    // Not used in this form, but required by interface
EndProcedure
```

### 2. Event Handler Placeholders

```bsl
// Reserved for future use with comment
Procedure BeforeWriteAtServer(Cancel, CurrentObject, WriteParameters)
    // Reserved for future validation logic
EndProcedure
```

### 3. Extension Points

```bsl
// Designed to be overridden
Procedure AfterProcessing() Export
    // Override in extension module
EndProcedure
```

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `allowMethodComments` | `False` | When True, methods with only comments are not flagged |
| `excludeMethodNamePattern` | `""` | Regex pattern for method names to exclude |

### Configuration Examples

```
# Allow methods with comments
allowMethodComments = True

# Exclude event handlers
excludeMethodNamePattern = On.*|Before.*|After.*
```

---

## 🔧 How to Fix

### Option 1: Implement the Method

```bsl
// Before
Procedure CalculateTotal() Export
EndProcedure

// After
Procedure CalculateTotal() Export
    Total = 0;
    For Each Row In Items Do
        Total = Total + Row.Amount;
    EndDo;
    Object.Total = Total;
EndProcedure
```

### Option 2: Remove the Method

If the method is not needed, delete it entirely.

### Option 3: Add Explanatory Comment

If intentionally empty, add a comment explaining why:

```bsl
Procedure BeforeDelete(Cancel) Export
    // Intentionally empty: Standard deletion behavior is sufficient
EndProcedure
```

### Option 4: Raise Not Implemented

If it's a stub that shouldn't be called:

```bsl
Procedure AbstractMethod() Export
    Raise "Method AbstractMethod must be overridden in derived module";
EndProcedure
```

---

## 📖 Common Scenarios

### Scenario 1: Forgotten TODO

```bsl
// ❌ Developer forgot to implement
Procedure SendNotification(User) Export
    // TODO
EndProcedure

// ✅ Implemented
Procedure SendNotification(User) Export
    Message = New UserMessage;
    Message.Text = "Notification for " + User;
    Message.Message();
EndProcedure
```

### Scenario 2: Removed Implementation

```bsl
// ❌ Code was removed but method kept
Function ValidateData(Data) Export
    // Old validation removed during refactoring
EndFunction

// ✅ Either delete or implement
Function ValidateData(Data) Export
    If Not ValueIsFilled(Data) Then
        Return False;
    EndIf;
    Return True;
EndFunction
```

### Scenario 3: Generated Code

```bsl
// ❌ Auto-generated empty handler
Procedure ItemOnChange(Item)
EndProcedure

// ✅ Either delete or implement
Procedure ItemOnChange(Item)
    RecalculateDependentFields();
EndProcedure
```

---

## 🔍 Technical Details

### What Is Checked

1. All procedure and function declarations
2. Method body content analysis
3. Excludes methods matching pattern

### What Constitutes "Empty"

| Content | Considered Empty |
|---------|------------------|
| No statements | ✅ Yes |
| Only whitespace | ✅ Yes |
| Only comments | Depends on setting |
| Return with no value | ❌ No |
| Any statement | ❌ No |

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ModuleEmptyMethodCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

