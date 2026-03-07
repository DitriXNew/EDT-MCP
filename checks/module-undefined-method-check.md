# module-undefined-method-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `module-undefined-method-check` |
| **Title** | Undefined method |
| **Description** | Checks for calls to procedures or functions that are not defined |
| **Severity** | `CRITICAL` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies calls to **procedures or functions that are not defined** in the current scope. Calling an undefined method will cause a runtime error.

### Why This Is Important

- **Runtime errors**: Undefined method calls crash the application
- **Typo detection**: Catches misspelled method names
- **Refactoring safety**: Detects broken references after renaming
- **Code quality**: Ensures code will execute correctly

---

## ❌ Error Example

### Error Message

```
Procedure or function "{MethodName}" is not defined
```

### Noncompliant Code Example

```bsl
// ❌ Calling undefined procedure
Procedure ProcessOrder(Order) Export
    ValidateOrder(Order); // ❌ Procedure doesn't exist
    Order.Write();
EndProcedure

// ❌ Calling undefined function
Procedure CalculateTotals() Export
    Total = GetOrderTotal(); // ❌ Function doesn't exist
    DisplayTotal(Total);
EndProcedure

// ❌ Typo in method name
Procedure SendNotification(User) Export
    NotifyUser(User); // ❌ Typo in name
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Code with Defined Methods

```bsl
// ✅ All called methods are defined
Procedure ProcessOrder(Order) Export
    ValidateOrder(Order);
    Order.Write();
EndProcedure

Procedure ValidateOrder(Order) // ✅ Procedure is defined
    If Not ValueIsFilled(Order.Customer) Then
        Raise "Customer is required";
    EndIf;
EndProcedure

// ✅ Function is defined
Procedure CalculateTotals() Export
    Total = GetOrderTotal();
    DisplayTotal(Total);
EndProcedure

Function GetOrderTotal() // ✅ Defined
    Return 100;
EndFunction

Procedure DisplayTotal(Total) // ✅ Defined
    Message(String(Total));
EndProcedure

// ✅ Correct method name
Procedure SendNotification(User) Export
    SendUserNotification(User);
EndProcedure

Procedure SendUserNotification(User) // ✅ Correct name, defined
    // Send notification logic
EndProcedure
```

---

## 📋 Common Causes

### 1. Typos in Method Names

```bsl
// ❌ Common typos
ProcesData()         // "Proces" instead of "Process"
CalcualteAmount()    // "Calcualte" instead of "Calculate"
UpdateSttaus()       // "Sttaus" instead of "Status"
```

### 2. Deleted Methods

```bsl
// Method was deleted but calls remain
Procedure OldProcess()
    LegacyProcedure(); // ❌ Was deleted
EndProcedure
```

### 3. Renamed Methods

```bsl
// Method renamed from SaveData to WriteData
Procedure Process()
    SaveData(); // ❌ Should be WriteData()
EndProcedure
```

### 4. Missing Common Module Reference

```bsl
// ❌ Calling without module prefix
Procedure Process()
    SendEmail(Address, Subject, Body); // ❌ Missing prefix
EndProcedure

// ✅ Correct with module prefix
Procedure Process()
    EmailModule.SendEmail(Address, Subject, Body);
EndProcedure
```

### 5. Compilation Context Mismatch

```bsl
// ❌ Server method called from client without decorator
&AtClient
Procedure ClientProcedure()
    LoadDataFromDatabase(); // ❌ Server-only method
EndProcedure

// ✅ Correct with server call
&AtClient
Procedure ClientProcedure()
    LoadDataFromDatabaseAtServer(); // Calls server wrapper
EndProcedure

&AtServer
Procedure LoadDataFromDatabaseAtServer()
    LoadDataFromDatabase();
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Verify method name spelling

```bsl
// Check for typos
ProcessDaat(); // Wrong
ProcessData(); // Correct
```

### Step 2: Check if method exists

Search for the method definition in:
- Current module
- Referenced common modules
- Object modules

### Step 3: Add module prefix if needed

```bsl
// Add common module reference
CommonModule.MethodName();
```

### Step 4: Create missing method

```bsl
Procedure MissingMethod()
    // Add implementation
EndProcedure
```

### Step 5: Check compilation directive

```bsl
// Ensure method is available in current context
&AtServer
Procedure ServerMethod()
EndProcedure

&AtClient
Procedure ClientMethod()
    ServerMethodAtServer(); // Use wrapper for cross-context calls
EndProcedure
```

---

## 📋 Method Resolution

### Resolution Order

1. Current module methods
2. Global context (platform methods)
3. Common modules (with prefix)
4. Object methods (on objects)

### Scope Examples

```bsl
// Local method - no prefix
LocalMethod();

// Common module method - with prefix
CommonModule.ExportMethod();

// Object method - on reference
ObjectRef.GetObject().ObjectMethod();

// Platform method - no prefix
Message("Text");
```

---

## 📖 Procedure vs Function

| Type | Returns Value | Usage |
|------|---------------|-------|
| Procedure | No | `MethodName();` |
| Function | Yes | `Result = MethodName();` |

### Both Covered by This Check

```bsl
// ❌ Undefined procedure
DoSomething();

// ❌ Undefined function
Result = GetSomething();
```

---

## ⚠️ Special Cases

### Dynamic Method Calls

```bsl
// Dynamic calls cannot be validated statically
MethodName = "Process" + Type;
Execute(MethodName + "()"); // Cannot check at design time
```

### Reflection

```bsl
// Reflection-based calls
If Metadata.CommonModules.Find(ModuleName) <> Undefined Then
    Execute(ModuleName + ".Method()"); // Dynamic
EndIf;
```

---

## 🔍 Technical Details

### What Is Checked

1. All method call statements
2. All function call expressions
3. Symbol resolution in current scope
4. Export method availability

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ModuleUndefinedMethodCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Module Undefined Function Check](module-undefined-function-check.md)
- [Module Undefined Variable Check](module-undefined-variable-check.md)
