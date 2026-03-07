# reading-attributes-from-database-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `reading-attributes-from-database-check` |
| **Title** | Reading single object attribute from the database |
| **Description** | Checks for reading single attributes via reference which loads entire object |
| **Severity** | `MAJOR` |
| **Type** | `PERFORMANCE` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies cases where **single attributes are read via object reference**, which causes the **entire object to be loaded** from the database. This is inefficient when you only need one or a few fields.

### Why This Is Important

- **Performance**: Loading entire object wastes resources
- **Memory**: Unnecessary data loaded into memory
- **Network**: More data transferred than needed
- **Scalability**: Impact grows with object size and frequency

---

## ❌ Error Example

### Error Message

```
When the {attribute} is read from the reference, the entire object is imported from the database
```

### Noncompliant Code Example

```bsl
// ❌ Reading attribute via reference (loads entire object)
Procedure ProcessCustomer(CustomerRef) Export
    CustomerName = CustomerRef.Description; // ❌ Loads entire customer object
    CustomerCode = CustomerRef.Code; // ❌ Already loaded, but pattern is bad
    
    Message(CustomerName);
EndProcedure

// ❌ In a loop - very inefficient
Procedure ProcessOrders(Orders) Export
    For Each Order In Orders Do
        CustomerName = Order.Customer.Description; // ❌ Loads customer for each order
        Message(CustomerName);
    EndDo;
EndProcedure

// ❌ Multiple attribute access
Function GetOrderInfo(OrderRef) Export
    Info = New Structure;
    Info.Insert("Number", OrderRef.Number); // ❌ Loads entire document
    Info.Insert("Date", OrderRef.Date);
    Info.Insert("Customer", OrderRef.Customer.Description); // ❌ Loads customer too
    Return Info;
EndFunction
```

---

## ✅ Compliant Solution

### Solution 1: Use Query

```bsl
// ✅ Query only needed attributes
Procedure ProcessCustomer(CustomerRef) Export
    Query = New Query;
    Query.Text = "SELECT Description FROM Catalog.Customers WHERE Ref = &Ref";
    Query.SetParameter("Ref", CustomerRef);
    
    Selection = Query.Execute().Select();
    If Selection.Next() Then
        CustomerName = Selection.Description; // ✅ Only Description loaded
        Message(CustomerName);
    EndIf;
EndProcedure

// ✅ Batch query for loop
Procedure ProcessOrders(OrderRefs) Export
    Query = New Query;
    Query.Text = 
        "SELECT 
        |    Orders.Ref,
        |    Orders.Customer.Description AS CustomerName
        |FROM Document.SalesOrder AS Orders
        |WHERE Orders.Ref IN (&OrderRefs)";
    Query.SetParameter("OrderRefs", OrderRefs);
    
    Selection = Query.Execute().Select();
    While Selection.Next() Do
        Message(Selection.CustomerName); // ✅ Efficient
    EndDo;
EndProcedure
```

### Solution 2: Use GetAttributes Method

```bsl
// ✅ GetAttributes - loads only specified attributes
Function GetOrderInfo(OrderRef) Export
    AttributeNames = "Number, Date, Customer";
    AttributeValues = Common.ObjectAttributeValues(OrderRef, AttributeNames);
    
    Info = New Structure;
    Info.Insert("Number", AttributeValues.Number);
    Info.Insert("Date", AttributeValues.Date);
    Info.Insert("Customer", AttributeValues.Customer.Description);
    Return Info;
EndFunction

// Common module function
Function ObjectAttributeValues(Ref, AttributeNames) Export
    Query = New Query;
    Query.Text = "SELECT " + AttributeNames + " FROM " + Ref.Metadata().FullName() + " WHERE Ref = &Ref";
    Query.SetParameter("Ref", Ref);
    
    Selection = Query.Execute().Select();
    If Selection.Next() Then
        Result = New Structure(AttributeNames);
        FillPropertyValues(Result, Selection);
        Return Result;
    EndIf;
    
    Return Undefined;
EndFunction
```

### Solution 3: Pre-load via Query Join

```bsl
// ✅ Join to get all needed data in one query
Procedure ProcessOrdersWithDetails(Filter) Export
    Query = New Query;
    Query.Text = 
        "SELECT 
        |    Orders.Ref,
        |    Orders.Number,
        |    Orders.Date,
        |    Customers.Description AS CustomerName,
        |    Customers.Code AS CustomerCode
        |FROM Document.SalesOrder AS Orders
        |LEFT JOIN Catalog.Customers AS Customers
        |ON Orders.Customer = Customers.Ref
        |WHERE Orders.Date >= &StartDate";
    Query.SetParameter("StartDate", Filter.StartDate);
    
    // All data loaded efficiently
    Selection = Query.Execute().Select();
    While Selection.Next() Do
        ProcessOrder(Selection);
    EndDo;
EndProcedure
```

---

## 📋 Performance Comparison

### Loading Patterns

| Pattern | Data Loaded | Efficiency |
|---------|-------------|------------|
| `Ref.Attribute` | Entire object | ❌ Poor |
| Query with specific fields | Only needed fields | ✅ Good |
| `GetAttributes()` | Only specified | ✅ Good |
| `GetObject()` then access | Entire object | ❌ Poor |

### Example Impact

```
Customer object size: 50 attributes
Need: 2 attributes

Via Ref.Attribute: Loads 50 attributes
Via Query: Loads 2 attributes
Savings: 96% less data
```

---

## 📖 When Reference Access Is Acceptable

### Small Objects

```bsl
// For very small objects (few attributes), difference is minimal
Status = OrderRef.Status;
```

### Already Loaded Objects

```bsl
// If you need most attributes anyway
Order = OrderRef.GetObject();
// Access multiple attributes - already loaded
```

### Single Access

```bsl
// One-time access in non-performance-critical code
If CustomerRef.IsVIP Then
    ApplyDiscount();
EndIf;
```

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `allowCompositeNonRefType` | `False` | Allow field access with composite non-reference type |

---

## 🔧 How to Fix

### Step 1: Identify attribute access via reference

Find patterns like `Ref.Attribute` or `Object.Field.SubField`.

### Step 2: Determine needed attributes

List all attributes you actually need.

### Step 3: Create efficient query

```bsl
Query.Text = "SELECT OnlyNeededFields FROM Object WHERE Ref = &Ref";
```

### Step 4: Use query results

Replace direct access with query results.

---

## 📋 Common Patterns

### Pattern: Attribute Lookup

```bsl
// ❌ Before
CustomerName = CustomerRef.Description;

// ✅ After
Query = New Query("SELECT Description FROM Catalog.Customers WHERE Ref = &Ref");
Query.SetParameter("Ref", CustomerRef);
CustomerName = Query.Execute().Unload()[0].Description;
```

### Pattern: Multiple Attributes

```bsl
// ❌ Before
Name = CustomerRef.Description;
Code = CustomerRef.Code;
IsVIP = CustomerRef.IsVIP;

// ✅ After
Query = New Query("SELECT Description, Code, IsVIP FROM Catalog.Customers WHERE Ref = &Ref");
Query.SetParameter("Ref", CustomerRef);
Result = Query.Execute().Unload()[0];
Name = Result.Description;
Code = Result.Code;
IsVIP = Result.IsVIP;
```

---

## 🔍 Technical Details

### What Is Checked

1. Attribute access via reference types
2. Field dereferencing patterns
3. Identifies full object loads

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ReadingAttributesFromDataBaseCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Query in Loop Check](query-in-loop-check.md)
- [1C Performance Best Practices](https://its.1c.ru/db/v8std)
