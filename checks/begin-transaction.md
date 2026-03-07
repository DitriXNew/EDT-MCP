# begin-transaction

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `begin-transaction` |
| **Title** | Begin transaction is incorrect |
| **Description** | Try-catch must be after begin transaction |
| **Severity** | `MINOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Disabled (needs to be enabled manually) |
| **1C Standard** | Transaction handling best practices |

---

## 🎯 What This Check Does

This check verifies that after calling `BeginTransaction()` (or `НачатьТранзакцию()`), the code correctly follows the transaction handling pattern:

1. **Try statement must immediately follow** `BeginTransaction()`
2. **No executable code** should be placed between `BeginTransaction()` and `Try`

### Why This Is Important

- **Data integrity**: Improper transaction handling can lead to data corruption
- **Resource leaks**: Transactions not properly closed can lock database resources
- **Error handling**: Without Try-Catch, exceptions won't be properly handled and transaction won't be rolled back
- **Best practice**: The 1C development standards define a specific pattern for transaction handling

---

## ❌ Error Examples

### Error Message 1: Try operator not found

```
The try operator was not found after calling begin transaction
```

**Russian:**
```
Не найден оператор "Попытка" после вызова "НачатьТранзакцию()"
```

#### Noncompliant Code Example

```bsl
Procedure Test()
    
    BeginTransaction();
    // ❌ ERROR: No Try statement after BeginTransaction
    
EndProcedure
```

### Error Message 2: Executable code between BeginTransaction and Try

```
There should be no executable code between begin transaction and try
```

**Russian:**
```
Между "НачатьТранзакцию()" и "Попытка" есть исполняемый код
```

#### Noncompliant Code Example

```bsl
Procedure Test()
    
    BeginTransaction();
    TestVariable = 1;  // ❌ ERROR: Executable code between BeginTransaction and Try
    Try
        // Business logic here
        CommitTransaction();
    Except
        RollbackTransaction();
        Raise;
    EndTry;
    
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Transaction Pattern

```bsl
Procedure Test()
    
    BeginTransaction();  // 1. Start transaction
    Try
        // 2. All locking and data processing logic is placed inside Try-Except block
        
        // Business logic here...
        ProcessData();
        
        // 3. At the end of processing, attempt to commit the transaction
        CommitTransaction();
    Except
        // 4. In case of any problems, first rollback the transaction...
        RollbackTransaction();
        
        // 5. ...then log the problem to event log...
        WriteLogEvent("MyModule.Test", EventLogLevel.Error, , , 
            ErrorDescription());
        
        // 6. ...after that, pass the problem to the calling code
        Raise;
    EndTry;
    
EndProcedure
```

### Complete Transaction Handling Pattern

```bsl
// Full transaction pattern with data locking
Procedure ProcessDocumentPosting(DocumentRef)
    
    BeginTransaction();
    Try
        // Lock data to prevent concurrent modifications
        Lock = New DataLock;
        LockItem = Lock.Add("AccumulationRegister.Inventory");
        LockItem.Mode = DataLockMode.Exclusive;
        LockItem.DataSource = DocumentRef.Inventory;
        Lock.Lock();
        
        // Process the data
        For Each Row In DocumentRef.Inventory Do
            // Processing logic...
        EndDo;
        
        // Commit if everything is OK
        CommitTransaction();
    Except
        // Always rollback on any exception
        RollbackTransaction();
        
        // Log the error
        WriteLogEvent("Document.Posting", EventLogLevel.Error,
            Metadata.Documents.MyDocument, DocumentRef, 
            ErrorDescription());
        
        // Re-raise the exception
        Raise;
    EndTry;
    
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Identify the issue

From the error message, determine which issue you have:
- **"Try was not found"** → Add Try-Except block after BeginTransaction
- **"Executable code between"** → Move code inside Try block

### Step 2: Add Try-Except block immediately after BeginTransaction

**Before (incorrect):**
```bsl
BeginTransaction();
// Some code here...
```

**After (correct):**
```bsl
BeginTransaction();
Try
    // Move all code here
    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
```

### Step 3: Move any code between BeginTransaction and Try inside the Try block

**Before (incorrect):**
```bsl
BeginTransaction();
PrepareData();  // ❌ This code should be inside Try
Try
    ProcessData();
    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
```

**After (correct):**
```bsl
BeginTransaction();
Try
    PrepareData();  // ✅ Now inside Try block
    ProcessData();
    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
```

### Step 4: Ensure proper exception handling

The Except block should:
1. **Always call `RollbackTransaction()`** first
2. **Log the error** to the event log (recommended)
3. **Re-raise the exception** with `Raise;` to notify the caller

---

## 📁 File Structure

This check applies to:

| File Type | Description |
|-----------|-------------|
| `Module.bsl` | Any BSL module file |
| Common modules | Shared business logic |
| Object modules | Catalog, Document object modules |
| Manager modules | Catalog, Document manager modules |
| Form modules | Form module code |

---

## 🔍 Technical Details

### Related Checks

This check is part of a transaction validation family:
- `begin-transaction` - Validates BeginTransaction usage
- `commit-transaction` - Validates CommitTransaction usage
- `rollback-transaction` - Validates RollbackTransaction usage
- `lock-out-of-try` - Checks that locks are inside Try blocks

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.BeginTransactionCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/BeginTransactionCheck.java
```

### Detected Method Names

The check recognizes both English and Russian method names:
- `BeginTransaction()` / `НачатьТранзакцию()`
- `CommitTransaction()` / `ЗафиксироватьТранзакцию()`
- `RollbackTransaction()` / `ОтменитьТранзакцию()`

---

## 📚 References

- [1C:Enterprise Development Standards - Exception Handling in Transactions](https://its.1c.ru/db/v8std#content:783:hdoc)
