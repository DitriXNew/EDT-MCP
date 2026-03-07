# rollback-transaction-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `rollback-transaction-check` |
| **Title** | Rollback transaction is incorrect |
| **Description** | Checks for correct usage of RollbackTransaction in exception handling |
| **Severity** | `CRITICAL` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [783](https://its.1c.ru/db/v8std/content/783/hdoc) |

---

## 🎯 What This Check Does

This check verifies the correct usage of **`RollbackTransaction()`**. It ensures rollback is properly placed in exception handling and follows the transaction pattern standard.

### Why This Is Important

- **Data integrity**: Incorrect rollback leaves data inconsistent
- **Transaction safety**: Proper exception handling prevents orphan transactions
- **Deadlock prevention**: Unreleased transactions can cause deadlocks
- **Error recovery**: Correct patterns allow proper error handling

---

## ❌ Error Examples

### Error Messages

```
Rollback transaction must be in a try-catch
```

```
There should be no executable code between exception and rollback transaction
```

```
There is no begin transaction for rollback transaction
```

```
There is no commit transaction for begin transaction
```

### Noncompliant Code Examples

```bsl
// ❌ RollbackTransaction outside try-catch
Procedure WriteData()
    BeginTransaction();
    WriteObject();
    CommitTransaction();
    
    If ErrorOccurred Then
        RollbackTransaction(); // ❌ Not in exception block
    EndIf;
EndProcedure

// ❌ Code between Exception and RollbackTransaction
Procedure ProcessData()
    BeginTransaction();
    Try
        WriteData();
        CommitTransaction();
    Except
        WriteLogEvent("Error"); // ❌ Code before rollback
        RollbackTransaction();
        Raise;
    EndTry;
EndProcedure

// ❌ RollbackTransaction without BeginTransaction
Procedure HandleError()
    Try
        DoWork();
    Except
        RollbackTransaction(); // ❌ No corresponding BeginTransaction
        Raise;
    EndTry;
EndProcedure

// ❌ BeginTransaction without Commit or Rollback
Procedure IncompleteTransaction()
    BeginTransaction();
    Try
        WriteData();
        // ❌ Missing CommitTransaction
    Except
        // ❌ Missing RollbackTransaction
        Raise;
    EndTry;
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Transaction Pattern

```bsl
// ✅ Complete and correct transaction handling
Procedure WriteData()
    BeginTransaction();
    Try
        // Transaction operations
        Object.Write();
        
        CommitTransaction();
    Except
        RollbackTransaction(); // ✅ Immediately after Except
        WriteLogEvent("WriteData", EventLogLevel.Error,
            , , ErrorDescription());
        Raise;
    EndTry;
EndProcedure
```

### Standard Pattern Explained

```bsl
// ✅ Standard transaction pattern
BeginTransaction();           // 1. Start transaction
Try
    // 2. Perform operations
    DoWork();
    
    CommitTransaction();      // 3. Commit on success
Except
    RollbackTransaction();    // 4. Rollback FIRST on error
    // 5. Then log/handle error
    HandleError();
    Raise;                    // 6. Re-raise if needed
EndTry;
```

---

## 📋 Transaction Rules

### Correct Pattern

| Step | In Try Block | In Except Block |
|------|--------------|-----------------|
| 1 | `BeginTransaction()` (before Try) | - |
| 2 | Operations | - |
| 3 | `CommitTransaction()` | - |
| 4 | - | `RollbackTransaction()` (first!) |
| 5 | - | Error handling |
| 6 | - | `Raise` (if needed) |

### Code Order in Except

```bsl
Except
    RollbackTransaction();    // ✅ MUST be first
    LogError();               // Then other operations
    NotifyUser();
    Raise;
EndTry;
```

---

## 📖 Why Order Matters

### Problem: Code Before Rollback

```bsl
Except
    WriteLogEvent(...);       // ❌ If this fails...
    RollbackTransaction();    // ...rollback never happens!
    Raise;
EndTry;
```

### Solution: Rollback First

```bsl
Except
    RollbackTransaction();    // ✅ Always executes
    WriteLogEvent(...);       // Safe to fail now
    Raise;
EndTry;
```

---

## 📋 Complete Examples

### Example 1: Simple Write

```bsl
// ✅ Correct pattern for object write
Procedure SaveCustomer(CustomerData) Export
    Customer = Catalogs.Customers.CreateItem();
    FillPropertyValues(Customer, CustomerData);
    
    BeginTransaction();
    Try
        Customer.Write();
        CommitTransaction();
    Except
        RollbackTransaction();
        WriteLogEvent("SaveCustomer", EventLogLevel.Error,
            Metadata.Catalogs.Customers, ,
            ErrorDescription());
        Raise;
    EndTry;
EndProcedure
```

### Example 2: Multiple Operations

```bsl
// ✅ Correct pattern for multiple writes
Procedure ProcessOrder(OrderData) Export
    BeginTransaction();
    Try
        // Create order
        Order = Documents.Order.CreateDocument();
        FillPropertyValues(Order, OrderData);
        Order.Write();
        
        // Create related records
        For Each Item In OrderData.Items Do
            CreateOrderItem(Order.Ref, Item);
        EndDo;
        
        // Update customer stats
        UpdateCustomerStats(OrderData.Customer);
        
        CommitTransaction();
    Except
        RollbackTransaction();
        WriteLogEvent("ProcessOrder", EventLogLevel.Error,
            , , ErrorDescription());
        Raise;
    EndTry;
EndProcedure
```

### Example 3: Nested Transactions

```bsl
// ✅ Handling nested transactions
Procedure OuterOperation() Export
    BeginTransaction();
    Try
        InnerOperation(); // May have its own transaction
        CommitTransaction();
    Except
        RollbackTransaction();
        Raise;
    EndTry;
EndProcedure
```

---

## 🔧 How to Fix

### Issue: Rollback Outside Try-Catch

```bsl
// Before
BeginTransaction();
WriteData();
If Error Then
    RollbackTransaction();
Else
    CommitTransaction();
EndIf;

// After
BeginTransaction();
Try
    WriteData();
    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
```

### Issue: Code Before Rollback

```bsl
// Before
Except
    LogError();
    RollbackTransaction();
    Raise;
EndTry;

// After
Except
    RollbackTransaction();
    LogError();
    Raise;
EndTry;
```

### Issue: Missing BeginTransaction

```bsl
// Before
Try
    WriteData();
Except
    RollbackTransaction(); // No matching Begin
EndTry;

// After - add BeginTransaction
BeginTransaction();
Try
    WriteData();
    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
```

---

## 🔍 Technical Details

### What Is Checked

1. RollbackTransaction placement
2. Matching BeginTransaction
3. Corresponding CommitTransaction
4. Code order in Except block

### Related Check

- [Begin Transaction Check](begin-transaction.md)
- [Commit Transaction Check](commit-transaction.md)

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.RollbackTransactionCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 783](https://its.1c.ru/db/v8std/content/783/hdoc)
- [Begin Transaction Check](begin-transaction.md)
- [Commit Transaction Check](commit-transaction.md)
