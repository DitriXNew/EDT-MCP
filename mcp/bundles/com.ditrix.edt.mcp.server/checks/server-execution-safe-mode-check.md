# server-execution-safe-mode-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `server-execution-safe-mode-check` |
| **Title** | Safe mode is not enabled when calling "Execute" or "Eval" |
| **Description** | Checks that safe mode is enabled before Execute or Eval calls on server |
| **Severity** | `CRITICAL` |
| **Type** | `SECURITY` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check ensures that **safe mode is enabled** before calling **`Execute()`** or **`Eval()`** on the server. These functions execute dynamic code and can be exploited for code injection attacks if not properly secured.

### Why This Is Important

- **Security**: Prevents code injection attacks
- **Privilege escalation**: Blocks unauthorized operations
- **Data protection**: Limits access to sensitive data
- **Compliance**: Security best practices requirement

---

## ❌ Error Example

### Error Messages

```
Safe mode is not enabled when calling "Execute"
```

```
Safe mode is not enabled when calling "Eval"
```

### Noncompliant Code Example

```bsl
// ❌ Execute without safe mode
&AtServer
Procedure ProcessDataAtServer()
    CodeToExecute = Parameters.CustomCode;
    Execute(CodeToExecute); // ❌ DANGEROUS - no safe mode
EndProcedure

// ❌ Eval without safe mode
&AtServer
Function CalculateExpressionAtServer(Expression)
    Return Eval(Expression); // ❌ DANGEROUS - no safe mode
EndFunction

// ❌ Dynamic method call without protection
&AtServer
Procedure CallMethodAtServer(MethodName)
    Execute(MethodName + "()"); // ❌ No safe mode
EndProcedure
```

---

## ✅ Compliant Solution

### Using SetSafeMode

```bsl
// ✅ Execute with safe mode enabled
&AtServer
Procedure ProcessDataAtServer()
    CodeToExecute = Parameters.CustomCode;
    
    SetSafeMode(True); // ✅ Enable safe mode
    Try
        Execute(CodeToExecute);
    Except
        SetSafeMode(False);
        Raise;
    EndTry;
    SetSafeMode(False);
EndProcedure

// ✅ Eval with safe mode
&AtServer
Function CalculateExpressionAtServer(Expression)
    SetSafeMode(True); // ✅ Enable safe mode
    Try
        Result = Eval(Expression);
    Except
        SetSafeMode(False);
        Raise;
    EndTry;
    SetSafeMode(False);
    Return Result;
EndFunction
```

### Using SafeModeManager

```bsl
// ✅ Using SafeModeManager for more control
&AtServer
Procedure ExecuteWithPermissions()
    Permissions = New Array;
    Permissions.Add(SafeModeManager.PermissionToUseFileSystem());
    
    SetSafeMode(True);
    SetSafeModeDisabled(Permissions);
    
    Try
        Execute(DynamicCode);
    Except
        SetSafeMode(False);
        Raise;
    EndTry;
    SetSafeMode(False);
EndProcedure
```

---

## 📋 Safe Mode Restrictions

### What Safe Mode Blocks

| Action | Blocked in Safe Mode |
|--------|---------------------|
| File system access | ✅ Yes |
| COM object creation | ✅ Yes |
| External connections | ✅ Yes |
| Privileged mode access | ✅ Yes |
| System commands | ✅ Yes |
| Add-in loading | ✅ Yes |
| Database queries | ❌ No (allowed) |
| Object manipulation | ❌ No (allowed) |

### Example of Blocked Operations

```bsl
SetSafeMode(True);

// These will fail in safe mode:
TextDocument.Read("C:\secret.txt"); // ❌ Blocked
COM = New COMObject("WScript.Shell"); // ❌ Blocked
Connection = New HTTPConnection(...); // ❌ Blocked

// These work in safe mode:
Query = New Query("SELECT * FROM Catalog.Products"); // ✅ Allowed
Object = Catalogs.Products.CreateItem(); // ✅ Allowed
```

---

## 📖 Correct Patterns

### Pattern 1: Simple Safe Mode

```bsl
// ✅ Basic pattern
&AtServer
Procedure ExecuteSafelyAtServer(Code)
    SetSafeMode(True);
    Execute(Code);
    SetSafeMode(False);
EndProcedure
```

### Pattern 2: With Exception Handling

```bsl
// ✅ Complete pattern with exception handling
&AtServer
Function EvalSafelyAtServer(Expression)
    SetSafeMode(True);
    Try
        Result = Eval(Expression);
    Except
        SetSafeMode(False);
        WriteLogEvent("Eval Error", EventLogLevel.Error,
            , , ErrorDescription());
        Raise;
    EndTry;
    SetSafeMode(False);
    Return Result;
EndFunction
```

### Pattern 3: With Specific Permissions

```bsl
// ✅ Safe mode with allowed permissions
&AtServer
Procedure ExecuteWithFileAccessAtServer(Code, FilePath)
    Permissions = New Array;
    Permissions.Add(SafeModeManager.PermissionToUseFileSystem(
        FilePath, True, False));
    
    SetSafeMode(True);
    SetSafeModeDisabled(Permissions);
    
    Try
        Execute(Code);
    Except
        SetSafeMode(False);
        Raise;
    EndTry;
    SetSafeMode(False);
EndProcedure
```

---

## ⚠️ Security Considerations

### Never Trust User Input

```bsl
// ❌ DANGEROUS - user input directly executed
&AtServer
Procedure DangerousAtServer(UserCode)
    Execute(UserCode); // User can do anything!
EndProcedure

// ✅ SAFER - validate and limit
&AtServer
Procedure SaferAtServer(UserExpression)
    // Validate expression is safe
    If Not IsValidExpression(UserExpression) Then
        Raise "Invalid expression";
    EndIf;
    
    SetSafeMode(True);
    Result = Eval(UserExpression);
    SetSafeMode(False);
EndProcedure
```

### Avoid Execute/Eval When Possible

```bsl
// ❌ Using Execute for dynamic call
Execute(MethodName + "()");

// ✅ Better: Use Call with known methods
If MethodName = "ProcessA" Then
    ProcessA();
ElsIf MethodName = "ProcessB" Then
    ProcessB();
EndIf;

// ✅ Or use a registry pattern
Handlers = New Map;
Handlers.Insert("ProcessA", "ProcessA");
Handlers.Insert("ProcessB", "ProcessB");
If Handlers.Get(MethodName) <> Undefined Then
    Execute(Handlers.Get(MethodName) + "()");
EndIf;
```

---

## 🔧 How to Fix

### Step 1: Identify Execute/Eval calls

Find all `Execute()` and `Eval()` calls in server code.

### Step 2: Add SetSafeMode

```bsl
// Before
Execute(DynamicCode);

// After
SetSafeMode(True);
Execute(DynamicCode);
SetSafeMode(False);
```

### Step 3: Add exception handling

```bsl
SetSafeMode(True);
Try
    Execute(DynamicCode);
Except
    SetSafeMode(False);
    Raise;
EndTry;
SetSafeMode(False);
```

### Step 4: Review necessity

Consider if Execute/Eval is really needed or can be replaced with safer alternatives.

---

## 🔍 Technical Details

### What Is Checked

1. `Execute()` calls on server
2. `Eval()` calls on server
3. Presence of `SetSafeMode(True)` before call

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ServerExecutionSafeModeCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [NotifyDescription To Server Procedure Check](notify-description-to-server-procedure-check.md)
