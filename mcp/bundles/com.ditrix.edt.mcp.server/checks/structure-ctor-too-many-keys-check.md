# structure-ctor-too-many-keys-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `structure-ctor-too-many-keys-check` |
| **Title** | Structure constructor has too many keys |
| **Description** | Checks that structure constructors don't have too many keys |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **structure constructors** that have **too many keys** defined inline. Long constructor calls are hard to read and maintain.

### Why This Is Important

- **Readability**: Long lines are hard to read
- **Maintainability**: Difficult to modify or add keys
- **Error-prone**: Easy to mismatch values with keys
- **Code review**: Harder to review inline structures

---

## ❌ Error Example

### Error Message

```
Structure constructor has more than {N} keys
```

### Noncompliant Code Example

```bsl
// ❌ Too many keys in constructor (exceeds limit)
Procedure CreateOrderData() Export
    OrderData = New Structure(
        "Number, Date, Customer, Manager, Warehouse, Currency, ExchangeRate, Total, VAT, Discount, PaymentMethod, DeliveryDate",
        OrderNumber, OrderDate, CustomerRef, ManagerRef, WarehouseRef, CurrencyRef, Rate, TotalAmount, VATAmount, DiscountAmount, PaymentType, DeliveryDate
    ); // ❌ 12 keys - too many
EndProcedure

// ❌ Single line with many keys
Result = New Structure("A, B, C, D, E, F, G, H, I, J", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
```

---

## ✅ Compliant Solution

### Solution 1: Use Insert Method

```bsl
// ✅ Build structure step by step
Procedure CreateOrderData() Export
    OrderData = New Structure;
    OrderData.Insert("Number", OrderNumber);
    OrderData.Insert("Date", OrderDate);
    OrderData.Insert("Customer", CustomerRef);
    OrderData.Insert("Manager", ManagerRef);
    OrderData.Insert("Warehouse", WarehouseRef);
    OrderData.Insert("Currency", CurrencyRef);
    OrderData.Insert("ExchangeRate", Rate);
    OrderData.Insert("Total", TotalAmount);
    OrderData.Insert("VAT", VATAmount);
    OrderData.Insert("Discount", DiscountAmount);
    OrderData.Insert("PaymentMethod", PaymentType);
    OrderData.Insert("DeliveryDate", DeliveryDate);
EndProcedure
```

### Solution 2: Use Grouped Structures

```bsl
// ✅ Group related fields into sub-structures
Procedure CreateOrderData() Export
    // Main order data
    OrderInfo = New Structure("Number, Date, Total", 
        OrderNumber, OrderDate, TotalAmount);
    
    // References
    References = New Structure("Customer, Manager, Warehouse",
        CustomerRef, ManagerRef, WarehouseRef);
    
    // Financial details
    Financial = New Structure("Currency, ExchangeRate, VAT, Discount",
        CurrencyRef, Rate, VATAmount, DiscountAmount);
    
    // Combine
    OrderData = New Structure;
    OrderData.Insert("Order", OrderInfo);
    OrderData.Insert("References", References);
    OrderData.Insert("Financial", Financial);
EndProcedure
```

### Solution 3: Builder Pattern

```bsl
// ✅ Create a builder function
Function NewOrderData() Export
    Return New Structure(
        "Number, Date, Customer, Manager, Warehouse, Currency, " +
        "ExchangeRate, Total, VAT, Discount, PaymentMethod, DeliveryDate");
EndFunction

Procedure ProcessOrder() Export
    OrderData = NewOrderData();
    FillPropertyValues(OrderData, OrderDocument);
EndProcedure
```

### Solution 4: Logical Grouping with Comments

```bsl
// ✅ If using Insert, group logically with comments
Procedure CreateOrderData() Export
    OrderData = New Structure;
    
    // Document identification
    OrderData.Insert("Number", OrderNumber);
    OrderData.Insert("Date", OrderDate);
    
    // Parties
    OrderData.Insert("Customer", CustomerRef);
    OrderData.Insert("Manager", ManagerRef);
    
    // Location
    OrderData.Insert("Warehouse", WarehouseRef);
    
    // Financial
    OrderData.Insert("Currency", CurrencyRef);
    OrderData.Insert("ExchangeRate", Rate);
    OrderData.Insert("Total", TotalAmount);
    OrderData.Insert("VAT", VATAmount);
    OrderData.Insert("Discount", DiscountAmount);
    
    // Delivery
    OrderData.Insert("PaymentMethod", PaymentType);
    OrderData.Insert("DeliveryDate", DeliveryDate);
EndProcedure
```

---

## 📋 Readability Comparison

### Inline Constructor (Hard to Read)

```bsl
// ❌ Which value goes with which key?
Data = New Structure("Name, Code, Price, Quantity, Amount, Discount, Tax, Total",
    ProductName, ProductCode, UnitPrice, Qty, LineAmount, DiscountPct, TaxAmount, LineTotal);
```

### Insert Method (Easy to Read)

```bsl
// ✅ Clear key-value associations
Data = New Structure;
Data.Insert("Name", ProductName);
Data.Insert("Code", ProductCode);
Data.Insert("Price", UnitPrice);
Data.Insert("Quantity", Qty);
Data.Insert("Amount", LineAmount);
Data.Insert("Discount", DiscountPct);
Data.Insert("Tax", TaxAmount);
Data.Insert("Total", LineTotal);
```

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxKeys` | 3 | Maximum allowed keys in structure constructor |

### Configuration Example

```
# Allow up to 5 keys in constructor
maxKeys = 5
```

---

## 🔧 How to Fix

### Step 1: Count keys

Count how many keys are in the constructor.

### Step 2: Choose refactoring approach

| Keys Count | Recommended Approach |
|------------|---------------------|
| 4-6 | Split into multiple lines or use Insert |
| 7-10 | Use Insert method |
| 10+ | Consider sub-structures or builder pattern |

### Step 3: Refactor

```bsl
// Before
Result = New Structure("A, B, C, D, E, F", 1, 2, 3, 4, 5, 6);

// After (using Insert)
Result = New Structure;
Result.Insert("A", 1);
Result.Insert("B", 2);
Result.Insert("C", 3);
Result.Insert("D", 4);
Result.Insert("E", 5);
Result.Insert("F", 6);
```

---

## 📖 When Inline Constructor Is Acceptable

### Few Keys

```bsl
// ✅ OK - only 2-3 keys
Point = New Structure("X, Y", 10, 20);
Range = New Structure("Start, End", StartDate, EndDate);
Credentials = New Structure("User, Password", Username, Pass);
```

### Template Creation (Keys Only)

```bsl
// ✅ OK - just defining structure template
Template = New Structure("Number, Date, Amount, Customer");
// Values filled later
FillPropertyValues(Template, Document);
```

---

## 🔍 Technical Details

### What Is Checked

1. Structure constructor calls
2. Key count in first parameter
3. Compares against maximum limit

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.StructureCtorTooManyKeysCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Method Too Many Parameters Check](method-too-many-params-check.md)
