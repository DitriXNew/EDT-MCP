# not-support-goto-operator-web-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `not-support-goto-operator-web-check` |
| **Title** | Check not support GoTo operator in web client |
| **Description** | Checks for Goto operator usage in code that runs on web client |
| **Severity** | `BLOCKER` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies usage of the **`Goto`** operator in **client-side code** that may run in the **web client**. The Goto operator is **not supported** in the web client and will cause runtime errors.

### Why This Is Important

- **Runtime error**: Code will fail in web client
- **Compatibility**: Breaks web client support
- **User impact**: Users cannot complete operations
- **Critical**: Must be fixed for web deployment

---

## ❌ Error Example

### Error Message

```
GoTo operator is not supported in the web client
```

### Noncompliant Code Example

```bsl
// ❌ Goto in client procedure
&AtClient
Procedure ProcessData()
    Counter = 0;
    
    ~Loop:
    Counter = Counter + 1;
    ProcessItem(Counter);
    
    If Counter < 10 Then
        Goto ~Loop; // ❌ FAILS in web client!
    EndIf;
EndProcedure

// ❌ Goto in client event handler
&AtClient
Procedure ItemOnChange(Item)
    If Not ValidateInput() Then
        Goto ~ShowError; // ❌ FAILS in web client!
    EndIf;
    
    ProcessChange();
    Return;
    
    ~ShowError:
    ShowMessageBox(, "Invalid input");
EndProcedure

// ❌ Goto in AtClientAtServer procedure
&AtClientAtServer
Procedure CommonLogic()
    If Condition Then
        Goto ~Skip; // ❌ FAILS when runs on web client!
    EndIf;
    
    DoWork();
    
    ~Skip:
EndProcedure
```

---

## ✅ Compliant Solution

### Use Structured Programming Constructs

```bsl
// ✅ Use While loop instead of Goto
&AtClient
Procedure ProcessData()
    Counter = 0;
    
    While Counter < 10 Do
        Counter = Counter + 1;
        ProcessItem(Counter);
    EndDo;
EndProcedure

// ✅ Use If-Else instead of Goto
&AtClient
Procedure ItemOnChange(Item)
    If Not ValidateInput() Then
        ShowMessageBox(, "Invalid input");
        Return;
    EndIf;
    
    ProcessChange();
EndProcedure

// ✅ Use If-Else in shared code
&AtClientAtServer
Procedure CommonLogic()
    If Not Condition Then
        DoWork();
    EndIf;
EndProcedure
```

---

## 📋 Affected Code Locations

### Where Goto Is Not Supported

| Context | Goto Support |
|---------|--------------|
| `&AtClient` | ❌ Not supported |
| `&AtClientAtServer` | ❌ Not supported (when on client) |
| `&AtClientAtServerNoContext` | ❌ Not supported |
| `&AtServer` | ✅ Supported |
| `&AtServerNoContext` | ✅ Supported |
| Server module code | ✅ Supported |

### Example of Server Code (Where Goto Works)

```bsl
// ✅ Goto works on server (but still not recommended)
&AtServer
Procedure ServerProcessAtServer()
    If Condition Then
        Goto ~Label; // Works on server
    EndIf;
    
    ~Label:
    DoWork();
EndProcedure
```

---

## 📖 Why Web Client Doesn't Support Goto

The web client compiles BSL code to JavaScript. JavaScript does not have a direct equivalent of the Goto statement, making it impossible to translate Goto logic to web client code.

### Technical Background

```
BSL Code (1C) → Compilation → JavaScript (Web)
     ↓                            ↓
  Supports Goto            No Goto support
```

---

## 🔧 How to Fix

### Step 1: Find all Goto in client code

Search for `Goto` in procedures marked with:
- `&AtClient`
- `&AtClientAtServer`
- `&AtClientAtServerNoContext`

### Step 2: Analyze control flow

Understand what the Goto is achieving.

### Step 3: Replace with structured construct

| Goto Pattern | Replacement |
|--------------|-------------|
| Loop | `While`, `For`, `For Each` |
| Skip code | `If-Else` |
| Early exit | `Return`, `Break`, `Continue` |
| Error handling | `Try-Catch` (on server) |

### Step 4: Test in web client

Verify the refactored code works in web browser.

---

## 📋 Refactoring Examples

### Example 1: Loop Pattern

```bsl
// ❌ Before
&AtClient
Procedure Process()
    I = 0;
    ~Start:
    I = I + 1;
    DoWork(I);
    If I < 10 Then Goto ~Start; EndIf;
EndProcedure

// ✅ After
&AtClient
Procedure Process()
    For I = 1 To 10 Do
        DoWork(I);
    EndDo;
EndProcedure
```

### Example 2: Conditional Jump

```bsl
// ❌ Before
&AtClient
Procedure Validate()
    If Not Check1() Then Goto ~Error; EndIf;
    If Not Check2() Then Goto ~Error; EndIf;
    ShowMessageBox(, "Success");
    Return;
    ~Error:
    ShowMessageBox(, "Error");
EndProcedure

// ✅ After
&AtClient
Procedure Validate()
    If Not Check1() Or Not Check2() Then
        ShowMessageBox(, "Error");
        Return;
    EndIf;
    ShowMessageBox(, "Success");
EndProcedure
```

### Example 3: Multi-Exit

```bsl
// ❌ Before
&AtClient
Procedure Process()
    If Condition1 Then Goto ~Exit; EndIf;
    DoWork1();
    If Condition2 Then Goto ~Exit; EndIf;
    DoWork2();
    ~Exit:
    Cleanup();
EndProcedure

// ✅ After
&AtClient
Procedure Process()
    If Not Condition1 Then
        DoWork1();
        If Not Condition2 Then
            DoWork2();
        EndIf;
    EndIf;
    Cleanup();
EndProcedure
```

---

## 🔍 Technical Details

### What Is Checked

1. Goto operator in client-side code
2. Labels in client-side code
3. Compilation context verification

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.NotSupportGotoOperatorWebCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Use Goto Operator Check](use-goto-operator-check.md)
- [Structured Programming](https://en.wikipedia.org/wiki/Structured_programming)
