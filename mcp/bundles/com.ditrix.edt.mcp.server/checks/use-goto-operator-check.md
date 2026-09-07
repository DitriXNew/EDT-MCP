# use-goto-operator-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `use-goto-operator-check` |
| **Title** | Use Goto operator |
| **Description** | Checks for usage of Goto operator and labels |
| **Severity** | `MAJOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies usage of the **`Goto`** operator and **labels** in BSL code. The Goto operator creates unstructured control flow and should be avoided in favor of structured programming constructs.

### Why This Is Important

- **Readability**: Goto makes code flow hard to follow
- **Maintainability**: Spaghetti code is difficult to modify
- **Debugging**: Hard to trace execution path
- **Best practices**: Structured programming is preferred
- **Web client**: Goto is not supported in web client

---

## ❌ Error Example

### Error Messages

```
Use Goto operator
```

```
Use Label with Goto operator
```

### Noncompliant Code Example

```bsl
// ❌ Using Goto for loop control
Procedure ProcessData() Export
    Counter = 0;
    
    ~StartLoop: // ❌ Label
    
    Counter = Counter + 1;
    ProcessItem(Counter);
    
    If Counter < 100 Then
        Goto ~StartLoop; // ❌ Goto operator
    EndIf;
EndProcedure

// ❌ Using Goto for error handling
Procedure ComplexProcess() Export
    If Not Step1() Then
        Goto ~ErrorHandler; // ❌ Goto
    EndIf;
    
    If Not Step2() Then
        Goto ~ErrorHandler; // ❌ Goto
    EndIf;
    
    If Not Step3() Then
        Goto ~ErrorHandler; // ❌ Goto
    EndIf;
    
    Return;
    
    ~ErrorHandler: // ❌ Label
    HandleError();
EndProcedure

// ❌ Using Goto to break nested loops
Procedure SearchMatrix() Export
    For I = 1 To 100 Do
        For J = 1 To 100 Do
            If Matrix[I][J] = Target Then
                Found = True;
                Goto ~ExitLoops; // ❌ Goto
            EndIf;
        EndDo;
    EndDo;
    
    ~ExitLoops: // ❌ Label
EndProcedure
```

---

## ✅ Compliant Solution

### Solution 1: Use Loops

```bsl
// ✅ Use For or While loop
Procedure ProcessData() Export
    For Counter = 1 To 100 Do
        ProcessItem(Counter);
    EndDo;
EndProcedure

// Alternative with While
Procedure ProcessData() Export
    Counter = 0;
    While Counter < 100 Do
        Counter = Counter + 1;
        ProcessItem(Counter);
    EndDo;
EndProcedure
```

### Solution 2: Use Try-Catch or Functions

```bsl
// ✅ Use try-catch for error handling
Procedure ComplexProcess() Export
    Try
        Step1();
        Step2();
        Step3();
    Except
        HandleError();
    EndTry;
EndProcedure

// ✅ Or use functions with early return
Procedure ComplexProcess() Export
    If Not Step1() Then
        HandleError();
        Return;
    EndIf;
    
    If Not Step2() Then
        HandleError();
        Return;
    EndIf;
    
    If Not Step3() Then
        HandleError();
        Return;
    EndIf;
EndProcedure
```

### Solution 3: Use Break or Return

```bsl
// ✅ Use flag and Break for nested loops
Procedure SearchMatrix() Export
    Found = False;
    
    For I = 1 To 100 Do
        For J = 1 To 100 Do
            If Matrix[I][J] = Target Then
                Found = True;
                Break; // Break inner loop
            EndIf;
        EndDo;
        
        If Found Then
            Break; // Break outer loop
        EndIf;
    EndDo;
EndProcedure

// ✅ Or extract to function and use Return
Function FindInMatrix(Matrix, Target)
    For I = 1 To 100 Do
        For J = 1 To 100 Do
            If Matrix[I][J] = Target Then
                Return New Structure("I, J", I, J); // ✅ Return
            EndIf;
        EndDo;
    EndDo;
    Return Undefined;
EndFunction
```

---

## 📋 Alternatives to Goto

| Goto Use Case | Structured Alternative |
|---------------|----------------------|
| Loop | `For`, `While`, `For Each` |
| Early exit | `Return`, `Break`, `Continue` |
| Error handling | `Try-Catch` |
| Nested loop exit | Flag + Break, or extract function |
| State machine | `If-ElsIf` or `Case` equivalent |

---

## 📖 Refactoring Patterns

### Pattern 1: Loop Replacement

```bsl
// ❌ Before (Goto loop)
~Loop:
DoWork();
If Condition Then
    Goto ~Loop;
EndIf;

// ✅ After (While loop)
While Condition Do
    DoWork();
EndDo;
```

### Pattern 2: Error Handling

```bsl
// ❌ Before (Goto error handler)
If Error1 Then Goto ~Error; EndIf;
If Error2 Then Goto ~Error; EndIf;
Return;
~Error:
HandleError();

// ✅ After (Try-catch or early return)
Try
    ProcessStep1();
    ProcessStep2();
Except
    HandleError();
EndTry;
```

### Pattern 3: Nested Loop Exit

```bsl
// ❌ Before (Goto exit)
For I = 1 To N Do
    For J = 1 To M Do
        If Found Then Goto ~Exit; EndIf;
    EndDo;
EndDo;
~Exit:

// ✅ After (Function with return)
Function FindElement()
    For I = 1 To N Do
        For J = 1 To M Do
            If Found Then
                Return New Structure("I, J", I, J);
            EndIf;
        EndDo;
    EndDo;
    Return Undefined;
EndFunction
```

---

## ⚠️ Web Client Limitation

### Goto Not Supported in Web Client

```bsl
// ❌ This will fail in web client
&AtClient
Procedure ClientMethod()
    If Condition Then
        Goto ~Label; // Error in web client!
    EndIf;
    
    ~Label:
    DoWork();
EndProcedure
```

See also: [Not Support Goto Operator Web Check](not-support-goto-operator-web-check.md)

---

## 🔧 How to Fix

### Step 1: Identify all labels and Goto statements

Find all `~Label:` and `Goto ~Label` in code.

### Step 2: Analyze the control flow

Understand what the Goto is trying to achieve.

### Step 3: Choose appropriate construct

| Purpose | Use Instead |
|---------|-------------|
| Repeat code | Loop |
| Skip code | If-Else |
| Exit early | Return, Break |
| Error handling | Try-Catch |

### Step 4: Refactor

Replace Goto with structured construct and remove labels.

---

## 🔍 Technical Details

### What Is Checked

1. `Goto` operator usage
2. Label declarations (`~LabelName:`)
3. Reports both labels and Goto statements

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.UseGotoOperatorCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Not Support Goto Operator Web Check](not-support-goto-operator-web-check.md)
- [Structured Programming](https://en.wikipedia.org/wiki/Structured_programming)
