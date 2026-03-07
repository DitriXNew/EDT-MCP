# configuration-data-lock-mode

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `configuration-data-lock-mode` |
| **Title** | Configuration data lock mode should be "Managed" |
| **Description** | Check that configuration data lock mode is set to Managed |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check validates that the configuration-level **Data Lock Mode** property is set to `Managed` (managed locks) rather than `Automatic` or `AutomaticAndManaged`.

### Why This Is Important

- **Performance**: Managed locks provide better control over database locking
- **Scalability**: Reduces lock contention in multi-user environments
- **Best practices**: Modern 1C development recommends managed locks
- **Predictability**: Developer explicitly controls lock behavior

---

## ❌ Error Example

### Error Message

```
Configuration data lock mode should be "Managed"
```

**Russian:**
```
Режим управления блокировкой данных конфигурации должен быть "Управляемый"
```

### Noncompliant Configuration

```xml
<!-- Configuration properties -->
<DataLockControlMode>Automatic</DataLockControlMode>  ❌ Not managed
```

Or:

```xml
<DataLockControlMode>AutomaticAndManaged</DataLockControlMode>  ❌ Mixed mode
```

---

## ✅ Compliant Solution

### Correct Configuration

```xml
<!-- Configuration properties -->
<DataLockControlMode>Managed</DataLockControlMode>  ✅ Managed locks
```

### In 1C:Enterprise Designer

1. Open **Configuration** properties
2. Find **Data lock mode** property
3. Set to **Managed**

---

## 📖 Data Lock Modes Explained

### Available Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| **Automatic** | Platform manages all locks | Legacy configurations |
| **Managed** | Developer controls locks explicitly | Modern configurations |
| **AutomaticAndManaged** | Both modes available per-object | Migration scenarios |

### Comparison

| Aspect | Automatic | Managed |
|--------|-----------|---------|
| Lock control | Platform | Developer |
| Lock granularity | Table/record | Explicit code |
| Performance | Lower | Higher |
| Complexity | Simple | Requires lock code |
| Scalability | Limited | Better |

---

## ⚠️ Automatic Lock Problems

### Why Avoid Automatic Locks

```bsl
// With Automatic locks, this code might create excessive locks:
BeginTransaction();
For Each Row In DocumentTable Do
    // Platform automatically locks entire tables
    // Even for simple reads
    Data = GetCatalogItem(Row.ItemRef);
EndDo;
CommitTransaction();
```

### Lock Escalation Issues

- Platform may lock entire tables instead of records
- Other users get blocked
- "Lock wait timeout" errors
- Reduced system throughput

---

## 📋 Migration to Managed Locks

### Step 1: Change configuration mode

Set Configuration → Data lock mode → **Managed**

### Step 2: Add explicit locks where needed

```bsl
// Before (automatic locks - implicit)
BeginTransaction();
DocumentObject = DocumentRef.GetObject();
DocumentObject.Status = Enums.Statuses.Approved;
DocumentObject.Write();
CommitTransaction();

// After (managed locks - explicit)
BeginTransaction();
Try
    // Explicit lock
    Lock = New DataLock;
    LockItem = Lock.Add("Document.Invoice");
    LockItem.SetValue("Ref", DocumentRef);
    Lock.Lock();
    
    DocumentObject = DocumentRef.GetObject();
    DocumentObject.Status = Enums.Statuses.Approved;
    DocumentObject.Write();
    
    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
```

### Step 3: Review all transactions

Check all `BeginTransaction()` calls and add appropriate `DataLock` operations.

---

## 🔧 How to Fix

### Step 1: Open Configuration properties

In 1C:Enterprise Designer:
1. Right-click on Configuration root
2. Select Properties
3. Find "Data lock mode" 

### Step 2: Change to Managed

Set value to **Managed**

### Step 3: Review and update code

Find all transactions and add explicit locks:

```bsl
// Template for managed locks
BeginTransaction();
Try
    Lock = New DataLock;
    LockItem = Lock.Add("Catalog.Products");
    LockItem.SetValue("Ref", ProductRef);
    LockItem.Mode = DataLockMode.Exclusive;
    Lock.Lock();
    
    // ... your code here ...
    
    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
```

---

## 📋 DataLock Examples

### Lock Single Record

```bsl
Lock = New DataLock;
LockItem = Lock.Add("Document.Invoice");
LockItem.SetValue("Ref", DocumentRef);
Lock.Lock();
```

### Lock Multiple Records

```bsl
Lock = New DataLock;
LockItem = Lock.Add("Catalog.Products");
LockItem.DataSource = ProductsTable;
LockItem.UseFromDataSource("Ref", "ProductRef");
Lock.Lock();
```

### Lock Information Register

```bsl
Lock = New DataLock;
LockItem = Lock.Add("InformationRegister.Prices");
LockItem.SetValue("Product", ProductRef);
LockItem.SetValue("PriceType", PriceTypeRef);
Lock.Lock();
```

---

## 🔍 Technical Details

### What Is Checked

1. Reads configuration metadata
2. Checks `DataLockControlMode` property
3. Reports if not set to `Managed`

### Check Implementation Class

```
com.e1c.v8codestyle.md.check.ConfigurationDataLockMode
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/check/
```

---

## 📚 References

- [Managed Locks Best Practices](https://its.1c.ru/)
