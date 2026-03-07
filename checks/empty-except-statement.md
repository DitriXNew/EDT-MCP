# empty-except-statement

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `empty-except-statement` |
| **Title** | Empty except statement |
| **Description** | Checks try-except-endtry statements for empty except statement |
| **Severity** | `MINOR` |
| **Type** | `CODE_STYLE` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [499](https://its.1c.ru/db/v8std/content/499/hdoc) |

---

## 🎯 What This Check Does

This check identifies **try-except** blocks where the **except** section is empty, meaning no error handling code is present.

### Why This Is Important

- **Silent failures**: Errors are swallowed without any action
- **Debugging difficulty**: No information about what went wrong
- **Data integrity**: Operations may fail silently, leaving data inconsistent
- **Best practices**: Always handle exceptions explicitly

---

## ❌ Error Example

### Error Message

```
Empty except statement
```

**Russian:**
```
Пустой блок исключения
```

### Noncompliant Code Example

```bsl
Procedure ProcessDocument(DocumentRef)
    Try
        DocumentObject = DocumentRef.GetObject();
        DocumentObject.Status = Enums.Statuses.Processed;
        DocumentObject.Write();
    Except
        // ❌ Empty except - error is silently ignored
    EndTry;
EndProcedure
```

---

## ✅ Compliant Solutions

### Option 1: Log the Error

```bsl
Procedure ProcessDocument(DocumentRef)
    Try
        DocumentObject = DocumentRef.GetObject();
        DocumentObject.Status = Enums.Statuses.Processed;
        DocumentObject.Write();
    Except
        // ✅ Log error information
        WriteLogEvent("Document.Processing", 
            EventLogLevel.Error,
            Metadata.Documents.SalesOrder,
            DocumentRef,
            ErrorDescription());
    EndTry;
EndProcedure
```

### Option 2: Re-throw with Context

```bsl
Procedure ProcessDocument(DocumentRef)
    Try
        DocumentObject = DocumentRef.GetObject();
        DocumentObject.Status = Enums.Statuses.Processed;
        DocumentObject.Write();
    Except
        // ✅ Re-throw with additional context
        ErrorText = StringFunctionsClientServer.SubstituteParametersToString(
            NStr("en = 'Failed to process document %1: %2'"),
            DocumentRef,
            ErrorDescription());
        Raise ErrorText;
    EndTry;
EndProcedure
```

### Option 3: Handle Specific Scenario

```bsl
Function TryGetObjectData(ObjectRef)
    Try
        Return ObjectRef.GetObject();
    Except
        // ✅ Return meaningful result for expected error scenario
        Return Undefined;
    EndTry;
EndFunction
```

### Option 4: Notify User

```bsl
Procedure ProcessDocumentClient(DocumentRef)
    Try
        ProcessDocumentServerCall(DocumentRef);
    Except
        // ✅ Inform user about the error
        ShowMessageBox(, NStr("en = 'Failed to process document. Please try again.'"));
    EndTry;
EndProcedure
```

---

## 📖 Exception Handling Patterns

### When to Use Try-Except

| Scenario | Example |
|----------|---------|
| External resources | File operations, web services |
| User input validation | Parsing numbers, dates |
| Optional operations | Non-critical updates |
| Transaction management | Database operations |

### What to Do in Except Block

| Action | When to Use |
|--------|-------------|
| Log error | Always for server-side errors |
| Re-throw | When caller should handle it |
| Return default | For optional/nullable operations |
| Notify user | Client-side interactive operations |
| Rollback | Transaction-related errors |

---

## 📋 Complete Error Handling Example

### Comprehensive Pattern

```bsl
Procedure ProcessDocumentWithFullHandling(DocumentRef, Cancel)
    
    BeginTransaction();
    Try
        Lock = New DataLock;
        LockItem = Lock.Add("Document.Invoice");
        LockItem.SetValue("Ref", DocumentRef);
        Lock.Lock();
        
        DocumentObject = DocumentRef.GetObject();
        DocumentObject.Status = Enums.Statuses.Processed;
        DocumentObject.Write();
        
        CommitTransaction();
        
    Except
        RollbackTransaction();
        
        // ✅ Log detailed error
        WriteLogEvent(
            "Document.Processing.Error",
            EventLogLevel.Error,
            Metadata.Documents.Invoice,
            DocumentRef,
            ErrorDescription());
        
        // ✅ Set cancel flag
        Cancel = True;
        
        // ✅ Provide user-friendly message
        CommonClientServer.MessageToUser(
            NStr("en = 'Failed to process document. See event log for details.'"));
            
    EndTry;
    
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Identify empty except blocks

Find all patterns like:
```bsl
Try
    // code
Except
    // empty or only comments
EndTry;
```

### Step 2: Determine appropriate handling

Ask yourself:
- Should the error be logged?
- Should the error be shown to the user?
- Should a default value be returned?
- Should the error be re-thrown?

### Step 3: Add appropriate error handling

Minimum requirement - log the error:
```bsl
Except
    WriteLogEvent("ModuleName.Operation", 
        EventLogLevel.Error,
        ,
        ,
        ErrorDescription());
EndTry;
```

---

## ⚠️ Common Mistakes

### Wrong: Comment Instead of Code

```bsl
Except
    // TODO: handle error later  ❌ Still empty!
EndTry;
```

### Wrong: Just Setting a Variable

```bsl
Except
    HasError = True;  // ❌ Error details are lost!
EndTry;
```

### Better: Include Error Information

```bsl
Except
    HasError = True;
    ErrorMessage = ErrorDescription();  // ✅ Preserve error info
EndTry;
```

---

## 🔍 Technical Details

### What Is Checked

1. Finds all `Try...Except...EndTry` statements
2. Checks if the `Except` section contains any statements
3. Reports if the except block is empty

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.EmptyExceptStatementCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 499](https://its.1c.ru/db/v8std/content/499/hdoc)
