# export-procedure-missing-comment

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `export-procedure-missing-comment` |
| **Title** | Export procedure (function) should be described by adding comment |
| **Description** | Checks that export procedures and functions have documentation comments |
| **Severity** | `MINOR` |
| **Type** | `CODE_STYLE` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [453](https://its.1c.ru/db/v8std/content/453/hdoc) |

---

## 🎯 What This Check Does

This check validates that **export procedures and functions** have a **documentation comment** describing their purpose, parameters, and return value.

### Why This Is Important

- **API documentation**: Users of your code need to understand how to use it
- **IntelliSense support**: Comments appear in code completion tooltips
- **Maintenance**: Future developers understand the code
- **Standards compliance**: Follows Standard 453

---

## ❌ Error Example

### Error Message

```
Export procedure (function) "{name}" should be described by adding comment
```

**Russian:**
```
Экспортная процедура (функция) "{name}" должна быть описана комментарием
```

### Noncompliant Code Example

```bsl
// ❌ No documentation comment
Function GetDocumentData(DocumentRef) Export
    Return DocumentRef.GetObject();
EndFunction

// ❌ Regular comment is not enough
// Gets document data
Function GetDocumentInfo(DocumentRef) Export
    Return DocumentRef.GetObject();
EndFunction
```

---

## ✅ Compliant Solution

### Correct Documentation

```bsl
// ✅ Proper documentation comment
// Gets document data by reference.
//
// Parameters:
//  DocumentRef - DocumentRef.Invoice - Reference to the document
//
// Returns:
//  DocumentObject.Invoice - Document object with all data
//
Function GetDocumentData(DocumentRef) Export
    Return DocumentRef.GetObject();
EndFunction
```

---

## 📖 Documentation Comment Format

### Standard Structure

```bsl
// Brief description of what the method does.
// Additional details if needed.
//
// Parameters:
//  ParamName1 - Type - Description of parameter
//  ParamName2 - Type - Description of parameter
//    (continued description if long)
//
// Returns:
//  Type - Description of return value
//
Function MethodName(ParamName1, ParamName2) Export
```

### For Procedures (No Return)

```bsl
// Processes the document and updates status.
//
// Parameters:
//  DocumentRef - DocumentRef.Invoice - Document to process
//  NewStatus - EnumRef.Statuses - New status to set
//
Procedure ProcessDocument(DocumentRef, NewStatus) Export
```

---

## 📋 Documentation Examples

### Function with Structure Return

```bsl
// Creates a new order structure with default values.
//
// Parameters:
//  Customer - CatalogRef.Customers - Customer for the order
//  OrderDate - Date - Order date (default is current date)
//
// Returns:
//  Structure - Order data:
//   * Customer - CatalogRef.Customers - Customer reference
//   * Date - Date - Order date
//   * Number - String - Auto-generated number
//   * Items - ValueTable - Empty items table
//
Function CreateOrder(Customer, OrderDate = Undefined) Export
    // Implementation
EndFunction
```

### Function with Array Return

```bsl
// Gets list of active products in category.
//
// Parameters:
//  Category - CatalogRef.ProductCategories - Category to filter by
//  IncludeSubcategories - Boolean - Include nested categories
//
// Returns:
//  Array of CatalogRef.Products - Active products
//
Function GetActiveProducts(Category, IncludeSubcategories = False) Export
    // Implementation
EndFunction
```

### Function that Can Return Undefined

```bsl
// Finds customer by tax ID.
//
// Parameters:
//  TaxID - String - Tax identification number
//
// Returns:
//  CatalogRef.Customers - Found customer reference
//  Undefined - If customer not found
//
Function FindCustomerByTaxID(TaxID) Export
    // Implementation
EndFunction
```

---

## 📋 Type Descriptions

### Common Types

| Type | Description Format |
|------|-------------------|
| String | `String` or `String(100)` |
| Number | `Number` or `Number(15,2)` |
| Date | `Date` |
| Boolean | `Boolean` |
| Reference | `CatalogRef.CatalogName` |
| Array | `Array of TypeName` |
| Structure | `Structure` with property list |
| ValueTable | `ValueTable` with column list |

### Composite Types

```bsl
// Parameters:
//  Value - String, Number, Date - Value to format
//  Target - CatalogRef.Products, CatalogRef.Services - Target object
```

---

## 🔧 How to Fix

### Step 1: Find undocumented export methods

Look for `Export` methods without preceding comments.

### Step 2: Add documentation comment

**Minimum requirement:**
```bsl
// Brief description of what it does.
//
Function MyFunction() Export
```

**Recommended:**
```bsl
// Brief description of what it does.
//
// Parameters:
//  Param1 - Type - Description
//
// Returns:
//  Type - Description
//
Function MyFunction(Param1) Export
```

### Step 3: Document all parameters

Every parameter should be documented with:
- Name (matching the actual parameter name)
- Type (specific 1C types)
- Description (what it's used for)

---

## ⚠️ Common Mistakes

### Wrong: Comment Too Far from Method

```bsl
// Gets document data
//
// Parameters:
//  Ref - DocumentRef

// This empty line breaks the association
Function GetData(Ref) Export  // ❌ Comment disconnected
```

### Wrong: Missing Parameters Section

```bsl
// Gets document data
Function GetData(Ref) Export  // ❌ Parameter not documented
```

### Correct: Continuous Comment Block

```bsl
// Gets document data
//
// Parameters:
//  Ref - DocumentRef.Invoice - Document reference
//
// Returns:
//  Structure - Document data
//
Function GetData(Ref) Export  // ✅ Connected, complete
```

---

## 🔍 Technical Details

### What Is Checked

1. Finds methods with `Export` keyword
2. Looks for documentation comment before the method
3. Reports if comment is missing or invalid

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ExportProcedureMissingCommentCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 453](https://its.1c.ru/db/v8std/content/453/hdoc)
