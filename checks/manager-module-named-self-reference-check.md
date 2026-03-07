# manager-module-named-self-reference-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `manager-module-named-self-reference-check` |
| **Title** | Excessive named self reference in manager module |
| **Description** | Checks for redundant explicit references to the manager module name within itself |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies unnecessary explicit self-references in **manager modules**. When calling methods within the same manager module, you don't need to specify the full object name.

### Why This Is Important

- **Code simplicity**: Removes redundant code
- **Readability**: Makes code cleaner and easier to read
- **Maintainability**: Less code to update when renaming objects
- **Consistency**: Follows 1C coding standards

---

## ❌ Error Example

### Error Message

```
Excessive named self reference
```

### Noncompliant Code Example

```bsl
// Manager module of Catalog.Products

// ❌ Redundant self-reference
Function GetProductByCode(Code) Export
    Return Catalogs.Products.FindByCode(Code); // ❌ Unnecessary "Catalogs.Products"
EndFunction

// ❌ Calling own method with full name
Procedure ProcessAllProducts() Export
    Selection = Catalogs.Products.Select(); // ❌
    While Selection.Next() Do
        Catalogs.Products.ProcessProduct(Selection.Ref); // ❌ Unnecessary
    EndDo;
EndProcedure

// ❌ Creating using full name
Function CreateNewProduct(Description) Export
    Product = Catalogs.Products.CreateItem(); // ❌
    Product.Description = Description;
    Product.Write();
    Return Product.Ref;
EndFunction
```

---

## ✅ Compliant Solution

### Correct Code Without Self-Reference

```bsl
// Manager module of Catalog.Products

// ✅ Direct call without self-reference
Function GetProductByCode(Code) Export
    Return FindByCode(Code);
EndFunction

// ✅ Using implicit self-reference
Procedure ProcessAllProducts() Export
    Selection = Select();
    While Selection.Next() Do
        ProcessProduct(Selection.Ref);
    EndDo;
EndProcedure

// ✅ CreateItem without prefix
Function CreateNewProduct(Description) Export
    Product = CreateItem();
    Product.Description = Description;
    Product.Write();
    Return Product.Ref;
EndFunction
```

---

## 📋 Available Methods Without Prefix

### Common Manager Module Methods

| Full Form | Short Form |
|-----------|------------|
| `Catalogs.Name.FindByCode()` | `FindByCode()` |
| `Catalogs.Name.FindByDescription()` | `FindByDescription()` |
| `Catalogs.Name.Select()` | `Select()` |
| `Catalogs.Name.CreateItem()` | `CreateItem()` |
| `Catalogs.Name.CreateFolder()` | `CreateFolder()` |
| `Catalogs.Name.EmptyRef()` | `EmptyRef()` |
| `Documents.Name.Select()` | `Select()` |
| `Documents.Name.CreateDocument()` | `CreateDocument()` |
| `Documents.Name.EmptyRef()` | `EmptyRef()` |

### Example with Documents

```bsl
// Manager module of Document.SalesOrder

// ❌ With redundant self-reference
Function FindOrderByNumber(Number) Export
    Return Documents.SalesOrder.FindByNumber(Number);
EndFunction

// ✅ Without self-reference
Function FindOrderByNumber(Number) Export
    Return FindByNumber(Number);
EndFunction
```

---

## ⚠️ When Self-Reference Is Needed

### Accessing Different Object

```bsl
// Manager module of Catalog.Products
// Accessing DIFFERENT catalog - full reference needed
Function GetCategory(CategoryCode) Export
    Return Catalogs.ProductCategories.FindByCode(CategoryCode); // ✅ Different object
EndFunction
```

### Passing as Parameter

```bsl
// Manager module of Catalog.Products
// Passing manager as parameter - might need full reference
Procedure RegisterInLog() Export
    WriteLogEvent("Products", EventLogLevel.Information,
        Catalogs.Products, // ✅ Needed for metadata reference
        , "Processing started");
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Identify self-references

Find calls like `Catalogs.SameCatalog.Method()` inside the manager module.

### Step 2: Remove the prefix

```bsl
// Before
Result = Catalogs.Products.FindByCode(Code);

// After
Result = FindByCode(Code);
```

### Step 3: Test the code

Ensure all method calls still work correctly.

---

## 📋 Comparison: With and Without

```bsl
// Manager module of Catalog.Customers

// ❌ Before (verbose)
Function GetVIPCustomers() Export
    Query = New Query;
    Query.Text = "SELECT Ref FROM Catalog.Customers WHERE IsVIP = TRUE";
    
    Result = New Array;
    Selection = Query.Execute().Select();
    While Selection.Next() Do
        Customer = Catalogs.Customers.GetRef(Selection.Ref);
        Data = Catalogs.Customers.GetCustomerData(Customer);
        Result.Add(Data);
    EndDo;
    
    Return Result;
EndFunction

// ✅ After (clean)
Function GetVIPCustomers() Export
    Query = New Query;
    Query.Text = "SELECT Ref FROM Catalog.Customers WHERE IsVIP = TRUE";
    
    Result = New Array;
    Selection = Query.Execute().Select();
    While Selection.Next() Do
        Customer = GetRef(Selection.Ref);
        Data = GetCustomerData(Customer);
        Result.Add(Data);
    EndDo;
    
    Return Result;
EndFunction
```

---

## 🔍 Technical Details

### What Is Checked

1. Scans manager module code
2. Identifies references to the same object
3. Reports redundant self-references

### Related Check

- [Common Module Named Self-Reference](common-module-named-self-reference.md)

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ManagerModuleNamedSelfReferenceCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Common Module Self-Reference Check](common-module-named-self-reference.md)
