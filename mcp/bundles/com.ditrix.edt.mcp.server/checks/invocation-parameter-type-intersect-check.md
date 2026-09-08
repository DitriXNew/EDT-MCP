# Invocation type intersects with parameter type

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `invocation-parameter-type-intersect` |
| **Severity** | Major |
| **Type** | Code style |
| **Category** | Strict Types |

## 🎯 What This Check Does

Strict typing system checks that type of invoked expression intersects with parameter type of called method. This prevents passing incompatible data types.

## ❌ Error Examples

### Example 1 - Incompatible types

```bsl
// @strict-types

// Parameters:
//  Amount - Number
//
Procedure ProcessAmount(Amount)
EndProcedure

// Calling code:
Value = "text";
ProcessAmount(Value);  // ← ERROR: String does not intersect with Number
```

### Example 2 - Wrong reference type

```bsl
// @strict-types

// Parameters:
//  Product - CatalogRef.Products
//
Procedure ProcessProduct(Product)
EndProcedure

Customer = Catalogs.Customers.FindByCode("001");
ProcessProduct(Customer);  // ← ERROR: CatalogRef.Customers ≠ CatalogRef.Products
```

## ✅ Compliant Solutions

### Example 1 - Matching types

```bsl
// @strict-types

// Parameters:
//  Amount - Number
//
Procedure ProcessAmount(Amount)
EndProcedure

Value = 100;
ProcessAmount(Value);  // Correct: Number matches Number
```

### Example 2 - Correct reference type

```bsl
// @strict-types

// Parameters:
//  Product - CatalogRef.Products
//
Procedure ProcessProduct(Product)
EndProcedure

Product = Catalogs.Products.FindByCode("001");
ProcessProduct(Product);  // Correct: CatalogRef.Products matches
```

### Example 3 - Union types

```bsl
// @strict-types

// Parameters:
//  Value - String, Number - any value
//
Procedure ProcessValue(Value)
EndProcedure

ProcessValue("text");  // OK: String is in union
ProcessValue(100);     // OK: Number is in union
```

## 🔧 How to Fix

1. Ensure passed value type matches parameter type
2. Check parameter documentation for expected types
3. Convert value to expected type if needed
4. Update parameter types if method should accept more types

### Configurable options:
- `allowDynamicTypesCheckForLocalMethodCall` - allow dynamic types for local calls

## 🔍 Technical Details

- **Java class**: `InvocationParamIntersectionCheck`
- **Location**: `com.e1c.v8codestyle.bsl.strict.check`
- **Applies when**: Module has `@strict-types` annotation

## 📚 References

- [Code typification](https://its.1c.ru/db/metod8dev#content:5930:hdoc)
