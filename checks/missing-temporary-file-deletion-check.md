# missing-temporary-file-deletion-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `missing-temporary-file-deletion-check` |
| **Title** | Missing temporary file deletion after use |
| **Description** | Checks that temporary files created with GetTempFileName are deleted after use |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check ensures that temporary files created using **`GetTempFileName()`** are properly deleted after use. Failing to delete temporary files leads to disk space accumulation and potential security issues.

### Why This Is Important

- **Disk space**: Temporary files accumulate and waste storage
- **Security**: Sensitive data may remain on disk
- **Performance**: Too many temp files can slow file operations
- **Cleanup**: Proper resource management

---

## ❌ Error Example

### Error Message

```
Missing temporary file deletion after use.
```

### Noncompliant Code Example

```bsl
// ❌ Temporary file not deleted
Procedure ExportDataToFile() Export
    TempFileName = GetTempFileName("xml");
    
    DataWriter = New XMLWriter;
    DataWriter.OpenFile(TempFileName);
    WriteDataToXML(DataWriter);
    DataWriter.Close();
    
    SendFileByEmail(TempFileName);
    // ❌ File is never deleted!
EndProcedure

// ❌ Exception path doesn't delete file
Procedure ProcessFile() Export
    TempFile = GetTempFileName("txt");
    
    TextWriter = New TextWriter(TempFile);
    Try
        WriteData(TextWriter);
        TextWriter.Close();
    Except
        // ❌ File not deleted on error
        Raise;
    EndTry;
EndProcedure

// ❌ Multiple temp files, only one deleted
Procedure ConvertDocument() Export
    SourceFile = GetTempFileName("doc");
    TargetFile = GetTempFileName("pdf");
    
    SaveDocumentTo(SourceFile);
    ConvertToPDF(SourceFile, TargetFile);
    
    DeleteFiles(SourceFile); // ❌ TargetFile not deleted!
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Code with File Deletion

```bsl
// ✅ Temporary file deleted after use
Procedure ExportDataToFile() Export
    TempFileName = GetTempFileName("xml");
    
    Try
        DataWriter = New XMLWriter;
        DataWriter.OpenFile(TempFileName);
        WriteDataToXML(DataWriter);
        DataWriter.Close();
        
        SendFileByEmail(TempFileName);
    Except
        WriteLogEvent("ExportData", EventLogLevel.Error);
        Raise;
    EndTry;
    
    DeleteFiles(TempFileName); // ✅ Always delete
EndProcedure

// ✅ Deletion in finally-like pattern
Procedure ProcessFile() Export
    TempFile = GetTempFileName("txt");
    ErrorOccurred = False;
    
    Try
        TextWriter = New TextWriter(TempFile);
        WriteData(TextWriter);
        TextWriter.Close();
    Except
        ErrorOccurred = True;
        WriteLogEvent("ProcessFile", EventLogLevel.Error);
    EndTry;
    
    // ✅ Always delete, regardless of success
    DeleteFiles(TempFile);
    
    If ErrorOccurred Then
        Raise;
    EndIf;
EndProcedure

// ✅ All temp files deleted
Procedure ConvertDocument() Export
    SourceFile = GetTempFileName("doc");
    TargetFile = GetTempFileName("pdf");
    
    Try
        SaveDocumentTo(SourceFile);
        ConvertToPDF(SourceFile, TargetFile);
        ProcessResult(TargetFile);
    Except
        WriteLogEvent("Convert", EventLogLevel.Error);
        Raise;
    EndTry;
    
    DeleteFiles(SourceFile); // ✅ Delete source
    DeleteFiles(TargetFile); // ✅ Delete target
EndProcedure
```

---

## 📋 Proper Temporary File Patterns

### Pattern 1: Simple Usage

```bsl
// ✅ Create, use, delete
Procedure SimpleExport() Export
    TempFile = GetTempFileName("csv");
    
    // Use the file
    WriteCSVData(TempFile);
    SendToExternalSystem(TempFile);
    
    // Clean up
    DeleteFiles(TempFile);
EndProcedure
```

### Pattern 2: With Exception Handling

```bsl
// ✅ Guaranteed cleanup with try-finally pattern
Procedure SafeExport() Export
    TempFile = GetTempFileName("xml");
    Success = False;
    
    Try
        ExportToFile(TempFile);
        ProcessExportedFile(TempFile);
        Success = True;
    Except
        WriteLogEvent("Export", EventLogLevel.Error, , , ErrorDescription());
    EndTry;
    
    // ✅ Always clean up
    If FileExists(TempFile) Then
        DeleteFiles(TempFile);
    EndIf;
    
    If Not Success Then
        Raise "Export failed";
    EndIf;
EndProcedure
```

### Pattern 3: Using Temporary Directory

```bsl
// ✅ Create temp directory for multiple files
Procedure BatchProcess() Export
    TempDir = GetTempFileName(); // Creates temp directory path
    CreateDirectory(TempDir);
    
    Try
        // Create multiple files in temp directory
        For Each Item In ItemsToProcess Do
            TempFile = TempDir + "\" + Item.Name + ".xml";
            ProcessItem(Item, TempFile);
        EndDo;
        
        // Process all files
        ProcessDirectory(TempDir);
    Except
        WriteLogEvent("BatchProcess", EventLogLevel.Error);
        Raise;
    EndTry;
    
    // ✅ Delete entire directory with all files
    DeleteFiles(TempDir);
EndProcedure
```

### Pattern 4: Return File to Caller

```bsl
// When caller is responsible for deletion
Function CreateTempReport() Export
    TempFile = GetTempFileName("pdf");
    GenerateReport(TempFile);
    Return TempFile; // Caller must delete!
EndFunction

// Caller handles deletion
Procedure ShowReport() Export
    ReportFile = CreateTempReport();
    
    Try
        OpenDocument(ReportFile);
        // User views the report
    Except
        // Handle error
    EndTry;
    
    // ✅ Caller deletes the file
    DeleteFiles(ReportFile);
EndProcedure
```

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `deleteFileMethods` | `DeleteFiles` | Comma-separated list of methods that delete files |

---

## 🔧 How to Fix

### Step 1: Find GetTempFileName calls

Search for all usages of `GetTempFileName()`.

### Step 2: Trace file lifecycle

Follow where the temp file path goes and how it's used.

### Step 3: Add DeleteFiles call

Ensure `DeleteFiles()` is called after the file is no longer needed.

### Step 4: Handle exceptions

Make sure file is deleted even when errors occur.

```bsl
TempFile = GetTempFileName("xml");
Try
    // Work with file
Except
    // Log error
EndTry;
DeleteFiles(TempFile); // ✅ Always runs
```

---

## ⚠️ Common Mistakes

### Mistake 1: Delete Before Complete

```bsl
// ❌ Deleting before processing completes
TempFile = GetTempFileName();
WriteDataAsync(TempFile);
DeleteFiles(TempFile); // ❌ File might still be in use!
```

### Mistake 2: Forget One Path

```bsl
// ❌ Not all execution paths delete
If Condition Then
    ProcessAndDelete(TempFile);
Else
    ProcessAlternative(TempFile);
    // ❌ Forgot to delete here!
EndIf;
```

### Mistake 3: Early Return

```bsl
// ❌ Early return without cleanup
Function Process() Export
    TempFile = GetTempFileName();
    
    If Not ValidInput() Then
        Return False; // ❌ TempFile not deleted!
    EndIf;
    
    // ... processing ...
    DeleteFiles(TempFile);
    Return True;
EndFunction
```

---

## 🔍 Technical Details

### What Is Checked

1. Finds `GetTempFileName()` calls
2. Tracks the variable storing the path
3. Verifies `DeleteFiles()` is called with that variable

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.MissingTemporaryFileDeletionCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

