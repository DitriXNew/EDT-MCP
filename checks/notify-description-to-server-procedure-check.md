# notify-description-to-server-procedure-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `notify-description-to-server-procedure` |
| **Title** | NotifyDescription references server procedure |
| **Description** | Checks that NotifyDescription does not reference a server procedure |
| **Severity** | `CRITICAL` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies cases where a **NotifyDescription** (callback) is created with a reference to a **server procedure**. NotifyDescription callbacks can only call client procedures.

### Why This Is Important

- **Runtime error**: Server procedures cannot be used as callbacks
- **Architecture violation**: NotifyDescription is for async client operations
- **User impact**: Application will fail during async operation completion

---

## ❌ Error Example

### Error Message

```
NotifyDescription cannot reference a server procedure. Callback must be a client method.
```

### Noncompliant Code Example

```bsl
// ❌ NotifyDescription referencing server procedure
&AtClient
Procedure StartAsyncOperation()
    // ❌ ERROR: "ProcessResultAtServer" is a server procedure!
    NotifyHandler = New NotifyDescription("ProcessResultAtServer", ThisObject);
    
    BeginPuttingFiles(NotifyHandler, , , UUID);
EndProcedure

&AtServer
Procedure ProcessResultAtServer(PlacedFiles, AdditionalParameters) Export
    // This will NEVER be called - causes runtime error!
    For Each File In PlacedFiles Do
        SaveFile(File);
    EndDo;
EndProcedure

// ❌ Another example with ShowQueryBox
&AtClient
Procedure ConfirmDeletion()
    NotifyHandler = New NotifyDescription("ConfirmDeletionResultAtServer", ThisObject);
    ShowQueryBox(NotifyHandler, "Delete selected items?", QuestionDialogMode.YesNo);
EndProcedure

&AtServer
Procedure ConfirmDeletionResultAtServer(Result, AdditionalParameters) Export
    // ❌ ERROR: Server procedure cannot be callback!
    If Result = DialogReturnCode.Yes Then
        DeleteItemsAtServer();
    EndIf;
EndProcedure

// ❌ Using AtServerNoContext
&AtClient
Procedure StartProcess()
    Handler = New NotifyDescription("ProcessCompletionAtServerNoContext", ThisObject);
    StartBackgroundJob(Handler);
EndProcedure

&AtServerNoContext
Procedure ProcessCompletionAtServerNoContext(Result, Params) Export
    // ❌ ERROR: AtServerNoContext is still a server procedure!
EndProcedure
```

---

## ✅ Compliant Solution

### Correct: Use Client Procedure as Callback

```bsl
// ✅ NotifyDescription referencing client procedure
&AtClient
Procedure StartAsyncOperation()
    // ✅ CORRECT: "ProcessResultAtClient" is a client procedure
    NotifyHandler = New NotifyDescription("ProcessResultAtClient", ThisObject);
    
    BeginPuttingFiles(NotifyHandler, , , UUID);
EndProcedure

&AtClient
Procedure ProcessResultAtClient(PlacedFiles, AdditionalParameters) Export
    // ✅ Client procedure receives callback
    If PlacedFiles = Undefined Then
        Return; // User cancelled
    EndIf;
    
    // Call server to process files
    ProcessFilesAtServer(PlacedFiles);
EndProcedure

&AtServer
Procedure ProcessFilesAtServer(PlacedFiles)
    For Each File In PlacedFiles Do
        SaveFile(File);
    EndDo;
EndProcedure
```

### Complete Async Pattern

```bsl
// ✅ Proper async pattern with callback chain
&AtClient
Procedure ConfirmDeletion()
    // Step 1: Create callback to CLIENT procedure
    NotifyHandler = New NotifyDescription("ConfirmDeletionResultAtClient", ThisObject);
    ShowQueryBox(NotifyHandler, "Delete selected items?", QuestionDialogMode.YesNo);
EndProcedure

&AtClient
Procedure ConfirmDeletionResultAtClient(Result, AdditionalParameters) Export
    // Step 2: Client procedure receives callback
    If Result = DialogReturnCode.Yes Then
        // Step 3: Call server to perform action
        DeleteItemsAtServer();
        ShowMessageBox(, "Items deleted");
    EndIf;
EndProcedure

&AtServer
Procedure DeleteItemsAtServer()
    // Step 4: Server performs actual work
    For Each SelectedItem In SelectedItems Do
        DeleteObject(SelectedItem);
    EndDo;
EndProcedure
```

---

## 📖 Understanding NotifyDescription

### How NotifyDescription Works

```
Client Code          Async Operation          Callback
    │                      │                     │
    │ ──Create Handler ─→  │                     │
    │ ──Start Operation ─→ │                     │
    │                      │ ←─ Operation ───────│
    │                      │    Completes        │
    │ ←─ Callback ────────────────────────────── │
    │    (Must be Client!)                       │
```

### Valid Callback Targets

| Compilation Directive | As Callback |
|----------------------|-------------|
| `&AtClient` | ✅ Valid |
| `&AtClientAtServer` | ✅ Valid (runs on client) |
| `&AtClientAtServerNoContext` | ✅ Valid |
| `&AtServer` | ❌ Invalid |
| `&AtServerNoContext` | ❌ Invalid |

---

## 📋 Common Async Operations

### Operations That Require NotifyDescription

| Method | Purpose |
|--------|---------|
| `BeginPuttingFiles` | Upload files to server |
| `BeginGettingFiles` | Download files from server |
| `ShowQueryBox` | Display dialog with callback |
| `ShowInputString` | Input string with callback |
| `ShowInputNumber` | Input number with callback |
| `ShowInputDate` | Input date with callback |
| `BeginRunningApplication` | Launch external app |
| `BeginAttachingFileSystemExtension` | Attach file system |

### Example with BeginGettingFiles

```bsl
// ✅ Correct pattern for file download
&AtClient
Procedure DownloadFile()
    Handler = New NotifyDescription("DownloadFileComplete", ThisObject);
    
    FileDescription = New TransferableFileDescription("report.xlsx");
    FilesToGet = New Array;
    FilesToGet.Add(FileDescription);
    
    BeginGettingFiles(Handler, FilesToGet);
EndProcedure

&AtClient
Procedure DownloadFileComplete(ReceivedFiles, AdditionalParameters) Export
    If ReceivedFiles = Undefined Then
        Return; // Cancelled
    EndIf;
    
    // Process on client or send to server
    If ReceivedFiles.Count() > 0 Then
        ShowMessageBox(, "File downloaded successfully");
    EndIf;
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Identify the callback procedure

Find the procedure name in NotifyDescription constructor.

### Step 2: Check the procedure's compilation directive

Look for `&AtServer` or `&AtServerNoContext`.

### Step 3: Change to client procedure

Either change the directive to `&AtClient` or create a new client procedure.

### Step 4: Move server logic

Call server from the client callback if needed.

---

## 📋 Refactoring Example

### Before (Incorrect)

```bsl
&AtClient
Procedure Start()
    Handler = New NotifyDescription("ProcessAtServer", ThisObject);
    ShowInputString(Handler, "", "Enter value:");
EndProcedure

&AtServer
Procedure ProcessAtServer(Value, Params) Export
    // ❌ Cannot be called as callback!
    Object.Field = Value;
    Write();
EndProcedure
```

### After (Correct)

```bsl
&AtClient
Procedure Start()
    Handler = New NotifyDescription("ProcessAtClient", ThisObject);
    ShowInputString(Handler, "", "Enter value:");
EndProcedure

&AtClient
Procedure ProcessAtClient(Value, Params) Export
    // ✅ Client procedure receives callback
    If Value = Undefined Then
        Return; // Cancelled
    EndIf;
    
    // Call server to save
    SaveValueAtServer(Value);
EndProcedure

&AtServer
Procedure SaveValueAtServer(Value)
    // Server saves the data
    Object.Field = Value;
    Write();
EndProcedure
```

---

## 🔍 Technical Details

### What Is Checked

1. NotifyDescription constructor calls
2. First parameter (procedure name)
3. Procedure compilation directive

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.NotifyDescriptionToServerProcedureCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

