# lock-out-of-try-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `lock-out-of-try-check` |
| **Title** | Lock out of Try |
| **Description** | Checks that Lock() method is called inside a try block |
| **Severity** | `MAJOR` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check ensures that the **`Lock()`** method of `DataLock` is called within a **try block**. This is required to properly handle lock acquisition failures and prevent deadlocks.

### Why This Is Important

- **Exception handling**: Lock failures throw exceptions that must be handled
- **Deadlock prevention**: Proper try-catch prevents hanging operations
- **Resource cleanup**: Ensures locks are properly released
- **User experience**: Allows graceful failure with user notification

---

## ❌ Error Example

### Error Message

```
Method Lock() out of try block
```

### Noncompliant Code Example

```bsl
// ❌ Lock outside of try block
Procedure WriteData(DataObject)
    Lock = New DataLock;
    LockItem = Lock.Add("Catalog.Products");
    LockItem.SetValue("Ref", DataObject.Ref);
    Lock.Lock(); // ❌ Not in try block
    
    DataObject.Write();
EndProcedure

// ❌ Lock before try
Procedure UpdateBalance(Account)
    Lock = New DataLock;
    LockItem = Lock.Add("AccumulationRegister.AccountBalance");
    LockItem.Mode = DataLockMode.Exclusive;
    Lock.Lock(); // ❌ Outside try
    
    Try
        // Process data
        RecordSet.Write();
    Except
        // Handle error
    EndTry;
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Code with Lock in Try Block

```bsl
// ✅ Lock inside try block
Procedure WriteData(DataObject)
    Lock = New DataLock;
    LockItem = Lock.Add("Catalog.Products");
    LockItem.SetValue("Ref", DataObject.Ref);
    
    Try
        Lock.Lock();
        DataObject.Write();
    Except
        Raise;
    EndTry;
EndProcedure

// ✅ Proper lock handling pattern
Procedure UpdateBalance(Account)
    Lock = New DataLock;
    LockItem = Lock.Add("AccumulationRegister.AccountBalance");
    LockItem.Mode = DataLockMode.Exclusive;
    
    Try
        Lock.Lock();
        
        // Process data
        RecordSet = AccumulationRegisters.AccountBalance.CreateRecordSet();
        RecordSet.Filter.Account.Set(Account);
        RecordSet.Read();
        
        // Modify and write
        RecordSet.Write();
    Except
        WriteLogEvent("UpdateBalance", EventLogLevel.Error,
            , , ErrorDescription());
        Raise;
    EndTry;
EndProcedure
```

---

## 📖 Understanding Data Locks

### Why Locks Need Try-Catch

| Scenario | What Happens |
|----------|--------------|
| Lock acquired | Normal execution continues |
| Lock timeout | Exception is thrown |
| Deadlock detected | Exception is thrown |
| Lock conflict | Exception is thrown |

### Lock Failure Example

```bsl
// Without try-catch:
Lock.Lock(); // If fails - unhandled exception, operation hangs

// With try-catch:
Try
    Lock.Lock();
    // Normal processing
Except
    // Can notify user, retry, or handle gracefully
    Message("Cannot acquire lock. Another user may be editing this data.");
EndTry;
```

---

## 📋 Proper Lock Patterns

### Pattern 1: Simple Lock

```bsl
// ✅ Basic lock pattern
Procedure ModifyProduct(ProductRef)
    Lock = New DataLock;
    LockItem = Lock.Add("Catalog.Products");
    LockItem.SetValue("Ref", ProductRef);
    
    Try
        Lock.Lock();
        
        Product = ProductRef.GetObject();
        Product.Description = "Updated";
        Product.Write();
    Except
        Raise;
    EndTry;
EndProcedure
```

### Pattern 2: Lock with Transaction

```bsl
// ✅ Lock with transaction
Procedure ProcessDocument(DocRef)
    Lock = New DataLock;
    LockItem = Lock.Add("Document.SalesOrder");
    LockItem.SetValue("Ref", DocRef);
    
    BeginTransaction();
    Try
        Lock.Lock();
        
        Doc = DocRef.GetObject();
        Doc.Status = Enums.DocumentStatus.Processed;
        Doc.Write();
        
        CommitTransaction();
    Except
        RollbackTransaction();
        Raise;
    EndTry;
EndProcedure
```

### Pattern 3: Multiple Locks

```bsl
// ✅ Multiple resources lock
Procedure TransferStock(SourceWarehouse, TargetWarehouse, Product, Quantity)
    Lock = New DataLock;
    
    LockItem = Lock.Add("AccumulationRegister.WarehouseStock");
    LockItem.SetValue("Warehouse", SourceWarehouse);
    LockItem.SetValue("Product", Product);
    
    LockItem = Lock.Add("AccumulationRegister.WarehouseStock");
    LockItem.SetValue("Warehouse", TargetWarehouse);
    LockItem.SetValue("Product", Product);
    
    BeginTransaction();
    Try
        Lock.Lock();
        
        // Perform transfer
        WriteStockMovement(SourceWarehouse, Product, -Quantity);
        WriteStockMovement(TargetWarehouse, Product, Quantity);
        
        CommitTransaction();
    Except
        RollbackTransaction();
        WriteLogEvent("TransferStock", EventLogLevel.Error);
        Raise;
    EndTry;
EndProcedure
```

---

## ⚠️ Common Mistakes

### Mistake 1: Lock Before Try

```bsl
// ❌ Wrong - lock before try
Lock = New DataLock;
Lock.Add("Catalog.Products");
Lock.Lock(); // Lock might fail here!
Try
    // Processing
Except
    // Won't catch lock failure
EndTry;
```

### Mistake 2: Empty Except

```bsl
// ❌ Wrong - swallowing lock exception
Try
    Lock.Lock();
    // Processing
Except
    // Empty - hides the problem!
EndTry;
```

### Mistake 3: No Lock at All

```bsl
// ❌ Wrong - no lock for concurrent access
Procedure UpdateCounter()
    Counter = GetCounter();
    Counter = Counter + 1;
    SetCounter(Counter); // Race condition!
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Identify Lock() calls outside try

Find all `Lock.Lock()` calls not inside try block.

### Step 2: Wrap in try-except

```bsl
// Before
Lock.Lock();
ProcessData();

// After
Try
    Lock.Lock();
    ProcessData();
Except
    Raise;
EndTry;
```

### Step 3: Add proper exception handling

```bsl
Try
    Lock.Lock();
    ProcessData();
Except
    WriteLogEvent("Operation", EventLogLevel.Error,
        , , ErrorDescription());
    Raise;
EndTry;
```

---

## 🔍 Technical Details

### What Is Checked

1. Scans for `DataLock` variable initialization
2. Finds corresponding `Lock()` method calls
3. Verifies the call is inside a try block

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.LockOutOfTryCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Begin Transaction Check](begin-transaction.md) - Related transaction handling
- [Commit Transaction Check](commit-transaction.md) - Transaction commit patterns
