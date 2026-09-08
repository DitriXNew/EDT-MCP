# code-after-async-call

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `code-after-async-call` |
| **Title** | The code should not follow an asynchronous call |
| **Description** | Checks that the asynchronous method is not followed by lines of code, since in this case the specified lines of code are executed immediately, without waiting for the asynchronous method to execute |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Disabled |

---

## 🎯 What This Check Does

This check validates that **no code follows an asynchronous method call**. In 1C:Enterprise, asynchronous methods execute in the background, and any code after them runs **immediately** without waiting for the async operation to complete.

### Why This Is Important

- **Unexpected behavior**: Code after async call executes before the async operation completes
- **Data consistency**: Results from async operations won't be available immediately
- **User confusion**: UI may update before the async operation finishes
- **Logic errors**: Dependent code may fail because async results aren't ready

---

## ❌ Error Example

### Error Message

```
The asynchronous method is followed by lines of code
```

**Russian:**
```
Асинхронный метод следует за строками кода
```

### Noncompliant Code Example

```bsl
&AtClient
Procedure Test()
    
    Text = "Warning text";
    ShowMessageBox(, Text);  // Async method
    Message("Closed the warning");  // ❌ This executes IMMEDIATELY, not after user closes the dialog!
    
EndProcedure
```

### What Happens

1. `ShowMessageBox()` opens dialog (async - doesn't wait)
2. `Message()` executes **immediately** - before user closes dialog
3. User sees "Closed the warning" message **before** closing the dialog!

---

## ✅ Compliant Solution

### Option 1: Use NotifyDescription callback

```bsl
&AtClient
Procedure Test()
    
    Text = "Warning text";
    Notification = New NotifyDescription("AfterShowWarning", ThisObject);
    ShowMessageBox(Notification, Text);
    // No code after async call!
    
EndProcedure

&AtClient
Procedure AfterShowWarning(AdditionalParameters) Export
    
    // ✅ This code runs AFTER user closes the dialog
    Message("Closed the warning");
    
EndProcedure
```

### Option 2: Use Await (platform 8.3.18+)

```bsl
&AtClient
Async Procedure Test()
    
    Text = "Warning text";
    Await ShowMessageBoxAsync(Text);  // Wait for user
    Message("Closed the warning");  // ✅ Now runs after dialog closes
    
EndProcedure
```

### Option 3: Return after async call

```bsl
&AtClient
Procedure Test()
    
    Text = "Warning text";
    ShowMessageBox(, Text);
    Return;  // ✅ Return immediately after async call
    
EndProcedure
```

---

## 📖 Common Async Methods

| Method | Description |
|--------|-------------|
| `ShowMessageBox()` | Show message dialog |
| `ShowInputString()` | Input string dialog |
| `ShowInputNumber()` | Input number dialog |
| `ShowInputDate()` | Input date dialog |
| `ShowInputValue()` | Input value dialog |
| `ShowQuestion()` | Yes/No/Cancel dialog |
| `ShowValue()` | Show value viewer |
| `BeginPutFile()` | Start file upload |
| `BeginGetFile()` | Start file download |
| `BeginRunningApplication()` | Start external app |

---

## ⚙️ Check Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `notifyDescriptionIsDefined` | Boolean | `true` | Only check if NotifyDescription parameter is defined |

---

## 🔧 How to Fix

### Step 1: Identify the async method

Look for methods that start with `Show`, `Begin`, or are known async operations.

### Step 2: Move post-execution code to callback

Create a `NotifyDescription` and move the code that should run after the async operation:

```bsl
// Before
ShowQuestion(, "Continue?", QuestionDialogMode.YesNo);
If Result = DialogReturnCode.Yes Then  // ❌ Result is undefined here!
    Process();
EndIf;

// After
Notification = New NotifyDescription("AfterQuestion", ThisObject);
ShowQuestion(Notification, "Continue?", QuestionDialogMode.YesNo);

Procedure AfterQuestion(Result, AdditionalParameters) Export
    If Result = DialogReturnCode.Yes Then  // ✅ Result is available
        Process();
    EndIf;
EndProcedure
```

---

## 📁 File Structure

This check applies to:

| File Type | Description |
|-----------|-------------|
| Form modules | Client-side form code |
| Command modules | Command module code |
| Common modules | Client common modules |

---

## 🔍 Technical Details

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.CodeAfterAsyncCallCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/CodeAfterAsyncCallCheck.java
```

