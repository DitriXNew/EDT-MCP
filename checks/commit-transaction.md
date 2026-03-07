# commit-transaction

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `commit-transaction` |
| **Title** | Commit transaction is incorrect |
| **Description** | Commit transaction must be in a try-catch, there should be no executable code between commit transaction and exception |
| **Severity** | `MINOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Disabled |

---

## 🎯 What This Check Does

This check validates proper usage of `CommitTransaction()` (or `ЗафиксироватьТранзакцию()`):

1. **CommitTransaction must be inside Try-Catch block**
2. **No executable code between CommitTransaction and Except**
3. **BeginTransaction must exist before CommitTransaction**
4. **RollbackTransaction must exist in Except block**
5. **Except block cannot be empty**

### Why This Is Important

- **Data integrity**: Improper commit handling can corrupt data
- **Error handling**: Exceptions after commit may leave system in inconsistent state
- **Transaction leaks**: Missing rollback can lock database resources
- **Best practices**: Follows 1C transaction handling standards

---

## ❌ Error Examples

### Error 1: CommitTransaction outside Try-Catch

```
Commit transaction must be in a try-catch
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    BeginTransaction();
    CommitTransaction();  // ❌ Not inside Try-Catch
    
EndProcedure
```

### Error 2: No BeginTransaction

```
There is no begin transaction for commit transaction
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    Try
        CommitTransaction();  // ❌ No BeginTransaction before
    Except
        RollbackTransaction();
    EndTry;
    
EndProcedure
```

### Error 3: No RollbackTransaction

```
There is no rollback transaction for begin transaction
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    BeginTransaction();
    Try
        CommitTransaction();
    Except
        // ❌ Missing RollbackTransaction
        Raise;
    EndTry;
    
EndProcedure
```

### Error 4: Code between CommitTransaction and Except

```
There should be no executable code between commit transaction and exception
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    BeginTransaction();
    Try
        ProcessData();
        CommitTransaction();
        LogSuccess();  // ❌ Code between Commit and Except can throw exception!
    Except
        RollbackTransaction();
        Raise;
    EndTry;
    
EndProcedure
```

### Error 5: Empty Except block

```
The transaction contains an empty exception block
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    BeginTransaction();
    Try
        CommitTransaction();
    Except
        // ❌ Empty except - swallows errors!
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
        // 2. All data processing logic
        ProcessData();
        
        // 3. Commit at the very end of Try block
        CommitTransaction();
    Except
        // 4. Always rollback first
        RollbackTransaction();
        
        // 5. Log the error
        WriteLogEvent("MyModule.Test", EventLogLevel.Error, , , 
            ErrorDescription());
        
        // 6. Re-raise the exception
        Raise;
    EndTry;
    // 7. Any logging should be AFTER EndTry, not before Except
    LogSuccess();
    
EndProcedure
```

### Complete Example with Data Lock

```bsl
Procedure ProcessDocument(DocumentRef)
    
    BeginTransaction();
    Try
        // Lock data
        Lock = New DataLock;
        LockItem = Lock.Add("AccumulationRegister.Inventory");
        LockItem.Mode = DataLockMode.Exclusive;
        Lock.Lock();
        
        // Process
        For Each Row In DocumentRef.Inventory Do
            UpdateInventory(Row);
        EndDo;
        
        // Commit as last statement in Try
        CommitTransaction();
    Except
        RollbackTransaction();
        WriteLogEvent("ProcessDocument", EventLogLevel.Error,
            Metadata.Documents.InventoryDocument, DocumentRef,
            ErrorDescription());
        Raise;
    EndTry;
    
EndProcedure
```

---

## 🔧 How to Fix

### Fix 1: Wrap in Try-Catch

```bsl
// Before
BeginTransaction();
ProcessData();
CommitTransaction();

// After
BeginTransaction();
Try
    ProcessData();
    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
```

### Fix 2: Add RollbackTransaction

```bsl
// Before
BeginTransaction();
Try
    CommitTransaction();
Except
    Raise;
EndTry;

// After
BeginTransaction();
Try
    CommitTransaction();
Except
    RollbackTransaction();  // Add this
    Raise;
EndTry;
```

### Fix 3: Move code after EndTry

```bsl
// Before
BeginTransaction();
Try
    CommitTransaction();
    LogSuccess();  // Problem
Except
    RollbackTransaction();
    Raise;
EndTry;

// After
BeginTransaction();
Try
    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
LogSuccess();  // Moved after EndTry
```

---

## 📁 File Structure

This check applies to:

| File Type | Description |
|-----------|-------------|
| `*.bsl` | Any BSL module file |
| Common modules | Shared business logic |
| Object modules | Catalog, Document modules |
| Manager modules | Manager modules |

---

## 🔍 Technical Details

### Related Checks

- `begin-transaction` - Validates BeginTransaction usage
- `commit-transaction` - Validates CommitTransaction usage (this check)
- `rollback-transaction` - Validates RollbackTransaction usage
- `lock-out-of-try` - Checks that locks are inside Try blocks

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.CommitTransactionCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/CommitTransactionCheck.java
```

---

## 📚 References

- [1C:Enterprise Development Standards - Transaction Handling](https://its.1c.ru/db/v8std#content:783:hdoc)
