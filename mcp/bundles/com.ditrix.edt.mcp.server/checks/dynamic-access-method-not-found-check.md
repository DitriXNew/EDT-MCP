# Method not found in object

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `dynamic-access-method-not-found` |
| **Severity** | Major |
| **Type** | Code style |
| **Category** | Strict Types |

## 🎯 What This Check Does

Strict typing system checks that dynamically called method exists in the object. This helps detect errors when calling non-existent methods.

## ❌ Error Examples

### Example 1 - Non-existent method

```bsl
// @strict-types

Object = New Structure;
Object.NonExistentMethod();  // ← ERROR: Method not found in accessed object
```

### Example 2 - Typo in method name

```bsl
// @strict-types

Query = New Query;
Query.SetParamter("Name", Value);  // ← ERROR: "SetParamter" - typo in "SetParameter"
```

### Example 3 - Wrong object type

```bsl
// @strict-types

Ref = Documents.SalesOrder.EmptyRef();
Ref.GetObject().WrongMethod();  // ← ERROR: Method not found in DocumentObject
```

## ✅ Compliant Solutions

### Example 1 - Use correct Structure methods

```bsl
// @strict-types

Object = New Structure;
Object.Insert("Key", Value);  // Correct method
```

### Example 2 - Correct method name

```bsl
// @strict-types

Query = New Query;
Query.SetParameter("Name", Value);  // Correct spelling
```

### Example 3 - Valid object methods

```bsl
// @strict-types

Ref = Documents.SalesOrder.EmptyRef();
Object = Ref.GetObject();
Object.Write();  // Valid method
```

## 🔧 How to Fix

1. Check spelling of method name
2. Verify method exists on the object type
3. Check object type is correct
4. Use IDE autocomplete to find correct methods

### Configurable options:
- `skipSourceObjectTypes` - Skip check if source object not found

## 🔍 Technical Details

- **Java class**: `DynamicFeatureAccessMethodNotFoundCheck`
- **Location**: `com.e1c.v8codestyle.bsl.strict.check`
- **Applies when**: Module has `@strict-types` annotation

## 📚 References

- [Code typification](https://its.1c.ru/db/metod8dev#content:5930:hdoc)
