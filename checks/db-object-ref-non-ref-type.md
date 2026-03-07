# db-object-ref-non-ref-type

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `db-object-ref-non-ref-type` |
| **Title** | Reference attribute should not have non-reference types |
| **Description** | Check that reference attributes don't include primitive types |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [728](https://its.1c.ru/db/v8std/content/728/hdoc) |

---

## 🎯 What This Check Does

This check validates that attributes intended for reference values do not include non-reference (primitive) types like `String`, `Number`, `Boolean`, `Date` in their composite type definition.

### Why This Is Important

- **Type consistency**: Reference attributes should only store references
- **Data integrity**: Prevents mixing references with primitive values
- **Query clarity**: Easier to write queries and reports
- **Code safety**: Type checking is more reliable
- **Standards compliance**: Follows Standard 728

---

## ❌ Error Example

### Error Message

```
Reference attribute should not have non-reference types
```

**Russian:**
```
Ссылочный реквизит не должен содержать не ссылочные типы
```

### Noncompliant Code Example

```xml
<!-- Catalog attribute definition -->
<Attribute>
    <Name>Customer</Name>
    <Type>
        <Type>CatalogRef.Customers</Type>
        <Type>String</Type>  ❌ Mixing reference with String
    </Type>
</Attribute>
```

**In Designer:**
- Attribute name: `Customer`
- Type: Composite type ❌
  - `CatalogRef.Customers`
  - `String` (length 100)

---

## ✅ Compliant Solution

### Option 1: Reference Type Only

```xml
<Attribute>
    <Name>Customer</Name>
    <Type>
        <Type>CatalogRef.Customers</Type>
    </Type>
</Attribute>
```

### Option 2: Separate Attributes

```xml
<!-- Reference attribute for existing customers -->
<Attribute>
    <Name>Customer</Name>
    <Type>
        <Type>CatalogRef.Customers</Type>
    </Type>
</Attribute>

<!-- Separate attribute for external customer name -->
<Attribute>
    <Name>ExternalCustomerName</Name>
    <Type>
        <Type>String</Type>
    </Type>
    <Length>100</Length>
</Attribute>
```

---

## 📖 Understanding the Problem

### Why Mixed Types Are Problematic

```bsl
// With mixed type (CatalogRef.Customers + String)
// Code becomes complex and error-prone:

If TypeOf(Document.Customer) = Type("CatalogRef.Customers") Then
    CustomerName = Document.Customer.Description;
ElsIf TypeOf(Document.Customer) = Type("String") Then
    CustomerName = Document.Customer;
Else
    // What else could it be?
EndIf;
```

### Proper Design

```bsl
// With separate attributes:
If ValueIsFilled(Document.Customer) Then
    CustomerName = Document.Customer.Description;
Else
    CustomerName = Document.ExternalCustomerName;
EndIf;
```

---

## 📋 Common Anti-Patterns

### Anti-Pattern 1: Reference + String

```
// ❌ Wrong: Customer can be reference or text
Customer: CatalogRef.Customers + String

// ✅ Correct: Separate attributes
Customer: CatalogRef.Customers
CustomerName: String  // For non-catalog customers
```

### Anti-Pattern 2: Reference + Number

```
// ❌ Wrong: Product can be reference or code
Product: CatalogRef.Products + Number

// ✅ Correct: Separate attributes
Product: CatalogRef.Products
ExternalProductCode: Number  // For external system codes
```

### Anti-Pattern 3: Reference + Boolean

```
// ❌ Wrong: Status can be reference or flag
Status: CatalogRef.Statuses + Boolean

// ✅ Correct: Only reference
Status: CatalogRef.Statuses
// Use Undefined or empty status catalog item for "no status"
```

---

## 🔧 How to Fix

### Step 1: Identify affected attributes

Find attributes with mixed types:
- Reference type(s) combined with
- String, Number, Boolean, Date, or other primitives

### Step 2: Analyze the use case

Why was primitive type added?
- Legacy data compatibility?
- External system integration?
- Special "no value" representation?

### Step 3: Refactor to separate attributes

**Before:**
```
Attribute: Supplier
  Type: CatalogRef.Suppliers + String
```

**After:**
```
Attribute: Supplier
  Type: CatalogRef.Suppliers

Attribute: ExternalSupplierName
  Type: String
  Length: 150
```

### Step 4: Update code and queries

```bsl
// Before (problematic)
If TypeOf(Object.Supplier) = Type("String") Then
    SupplierName = Object.Supplier;
Else
    SupplierName = Object.Supplier.Description;
EndIf;

// After (clean)
If ValueIsFilled(Object.Supplier) Then
    SupplierName = Object.Supplier.Description;
Else
    SupplierName = Object.ExternalSupplierName;
EndIf;
```

### Step 5: Migrate existing data

```bsl
// Data migration procedure
Selection = Catalogs.MyObject.Select();
While Selection.Next() Do
    If TypeOf(Selection.Supplier) = Type("String") Then
        Object = Selection.GetObject();
        Object.ExternalSupplierName = Object.Supplier;
        Object.Supplier = Catalogs.Suppliers.EmptyRef();
        Object.Write();
    EndIf;
EndDo;
```

---

## 📋 Alternative Designs

### Using Undefined for Empty Values

```bsl
// Instead of mixing reference + primitive for "no value"
// Just use empty reference or Undefined

If Not ValueIsFilled(Document.Customer) Then
    // Handle case when customer is not set
EndIf;
```

### Using Defined Types

```xml
<DefinedType>
    <Name>AllCustomers</Name>
    <Type>
        <Type>CatalogRef.Customers</Type>
        <Type>CatalogRef.Counterparties</Type>
        <!-- Only reference types! -->
    </Type>
</DefinedType>
```

---

## 🔍 Technical Details

### What Is Checked

1. Examines attribute type definitions
2. Identifies composite types that mix:
   - Reference types (CatalogRef, DocumentRef, etc.)
   - Primitive types (String, Number, Boolean, Date)
3. Reports each mixed-type attribute

### Check Implementation Class

```
com.e1c.v8codestyle.md.check.DbObjectRefNonRefType
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 728](https://its.1c.ru/db/v8std/content/728/hdoc)
