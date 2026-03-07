# use-non-recommended-methods-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `use-non-recommended-methods-check` |
| **Title** | Use of non-recommended methods |
| **Description** | Checks for usage of platform methods that are deprecated or not recommended |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies usage of **deprecated** or **non-recommended** platform methods. These methods may still work but have better alternatives or may be removed in future platform versions.

### Why This Is Important

- **Future compatibility**: Deprecated methods may be removed
- **Best practices**: Recommended methods are more reliable
- **Performance**: New methods often perform better
- **Maintainability**: Modern code uses current APIs

---

## ❌ Error Example

### Error Message

```
Method 'MethodName' is deprecated. Use 'RecommendedMethod' instead.
```

### Noncompliant Code Example

```bsl
// ❌ Using deprecated methods
Procedure ProcessDocument()
    // ❌ Deprecated: CurrentDate()
    DocumentDate = CurrentDate();
    
    // ❌ Deprecated: Message() for user notifications
    Message("Processing started");
    
    // ❌ Deprecated: Deprecated file methods
    TextDoc = New TextDocument;
    TextDoc.Read("C:\file.txt"); // Deprecated synchronous method
    
    // ❌ Deprecated dialog methods
    FileName = "";
    If GetFile("http://example.com/file.zip", FileName) Then  // Deprecated sync
        ProcessFile(FileName);
    EndIf;
EndProcedure

// ❌ Deprecated form methods
&AtClient
Procedure ProcessAtClient()
    // ❌ Deprecated: DoMessageBox
    DoMessageBox("Complete!");
    
    // ❌ Deprecated: OpenFormModal
    Result = OpenFormModal("CommonForm.SelectValue");
EndProcedure
```

---

## ✅ Compliant Solution

### Use Recommended Methods

```bsl
// ✅ Using recommended methods
Procedure ProcessDocument()
    // ✅ Use CurrentSessionDate() or CurrentUniversalDate()
    DocumentDate = CurrentSessionDate();
    
    // ✅ Use ShowUserNotification for messages
    ShowUserNotification(
        NStr("en = 'Processing'"),
        ,
        NStr("en = 'Processing started'"));
    
    // ✅ Use async file methods
    TextDoc = New TextDocument;
    TextDoc.BeginReading(New NotifyDescription("ReadingComplete", ThisObject), "C:\file.txt");
EndProcedure

// ✅ Use async dialog methods
&AtClient
Procedure ProcessAtClient()
    // ✅ Use ShowMessageBox with callback
    ShowMessageBox(, NStr("en = 'Complete!'"));
    
    // ✅ Use OpenForm with callback
    NotifyHandler = New NotifyDescription("SelectionComplete", ThisObject);
    OpenForm("CommonForm.SelectValue", , , , , , NotifyHandler);
EndProcedure

&AtClient
Procedure SelectionComplete(Result, AdditionalParameters) Export
    If Result <> Undefined Then
        ProcessSelectedValue(Result);
    EndIf;
EndProcedure
```

---

## 📋 Deprecated Methods and Alternatives

### Date and Time Methods

| Deprecated | Recommended | Notes |
|------------|-------------|-------|
| `CurrentDate()` | `CurrentSessionDate()` | Session date respects time zones |
| | `CurrentUniversalDate()` | UTC time |
| | `CurrentDate()` | Still valid for server-only code |

### Message Methods

| Deprecated | Recommended | Notes |
|------------|-------------|-------|
| `Message()` | `ShowUserNotification()` | For user notifications |
| | `CommonModule.MessageToUser()` | For field-related messages |
| `DoMessageBox()` | `ShowMessageBox()` | Async version |
| `DoQueryBox()` | `ShowQueryBox()` | Async version |

### File Operations

| Deprecated (Sync) | Recommended (Async) |
|-------------------|---------------------|
| `GetFile()` | `BeginGettingFiles()` |
| `PutFile()` | `BeginPuttingFiles()` |
| `CopyFile()` | `BeginCopyingFile()` |
| `DeleteFiles()` | `BeginDeletingFiles()` |
| `TextDocument.Read()` | `TextDocument.BeginReading()` |
| `TextDocument.Write()` | `TextDocument.BeginWriting()` |

### Form Operations

| Deprecated | Recommended | Notes |
|------------|-------------|-------|
| `OpenFormModal()` | `OpenForm()` with callback | Use NotifyDescription |
| `InputString()` | `ShowInputString()` | Async version |
| `InputNumber()` | `ShowInputNumber()` | Async version |
| `InputDate()` | `ShowInputDate()` | Async version |

---

## 📋 Migration Examples

### Example 1: CurrentDate to CurrentSessionDate

```bsl
// ❌ Before
Document.Date = CurrentDate();

// ✅ After
Document.Date = CurrentSessionDate();
```

### Example 2: Message to ShowUserNotification

```bsl
// ❌ Before
Message("Document saved successfully");

// ✅ After
ShowUserNotification(
    NStr("en = 'Document'"),
    GetURL(Document.Ref),
    NStr("en = 'Document saved successfully'"),
    PictureLib.Information);
```

### Example 3: DoMessageBox to ShowMessageBox

```bsl
// ❌ Before
&AtClient
Procedure Notify()
    DoMessageBox("Operation complete!");
    ContinueProcessing();
EndProcedure

// ✅ After
&AtClient
Procedure Notify()
    Handler = New NotifyDescription("NotifyComplete", ThisObject);
    ShowMessageBox(Handler, "Operation complete!");
EndProcedure

&AtClient
Procedure NotifyComplete(AdditionalParameters) Export
    ContinueProcessing();
EndProcedure
```

### Example 4: GetFile to BeginGettingFiles

```bsl
// ❌ Before
&AtClient
Procedure DownloadFile()
    FileName = "";
    If GetFile(FileURL, FileName) Then
        ProcessFile(FileName);
    EndIf;
EndProcedure

// ✅ After
&AtClient
Procedure DownloadFile()
    Handler = New NotifyDescription("DownloadComplete", ThisObject);
    
    FileDescription = New TransferableFileDescription(, FileURL);
    FilesToGet = New Array;
    FilesToGet.Add(FileDescription);
    
    BeginGettingFiles(Handler, FilesToGet);
EndProcedure

&AtClient
Procedure DownloadComplete(ReceivedFiles, AdditionalParameters) Export
    If ReceivedFiles <> Undefined And ReceivedFiles.Count() > 0 Then
        ProcessFile(ReceivedFiles[0].Name);
    EndIf;
EndProcedure
```

### Example 5: OpenFormModal to OpenForm

```bsl
// ❌ Before
&AtClient
Procedure SelectValue()
    Result = OpenFormModal("CommonForm.SelectValue", Parameters);
    If Result <> Undefined Then
        ProcessValue(Result);
    EndIf;
EndProcedure

// ✅ After
&AtClient
Procedure SelectValue()
    Handler = New NotifyDescription("SelectValueComplete", ThisObject);
    OpenForm("CommonForm.SelectValue", Parameters, , , , , Handler);
EndProcedure

&AtClient
Procedure SelectValueComplete(Result, AdditionalParameters) Export
    If Result <> Undefined Then
        ProcessValue(Result);
    EndIf;
EndProcedure
```

---

## 📋 Platform Version Notes

### Methods Deprecated Since 8.3.10+

- Synchronous file operations
- Modal dialog methods
- Some print methods

### Still Valid But Not Recommended

- `Message()` - works but notifications are better
- `CurrentDate()` - works but session date is more accurate

---

## 🔧 How to Fix

### Step 1: Identify deprecated method

Check the warning message for the method name.

### Step 2: Find recommended alternative

Refer to the table above or platform documentation.

### Step 3: Refactor to async pattern

Most replacements require NotifyDescription.

### Step 4: Test thoroughly

Ensure async behavior works correctly.

---

## 🔍 Technical Details

### What Is Checked

1. Method calls in BSL code
2. Comparison against deprecated method list
3. Platform version compatibility

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.UseNonRecommendedMethodsCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

