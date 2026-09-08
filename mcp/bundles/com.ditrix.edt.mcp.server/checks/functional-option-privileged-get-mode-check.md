# Privileged mode when getting functional option

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `functional-option-privileged-get-mode` |
| **Severity** | Major |
| **Type** | Warning |
| **Standard** | № 689 |

## 🎯 What This Check Does

Checks that functional option has "Privileged mode on get" flag set. This is necessary for proper functioning of functional options regardless of current user rights.

## ❌ Error Examples

### Example 1 - Flag not set

**Functional option XML:**
```xml
<FunctionalOption>
  <Name>UseWarehouse</Name>
  <PrivilegedModeOnGet>false</PrivilegedModeOnGet>  <!-- ERROR -->
</FunctionalOption>
```

## ✅ Compliant Solutions

### Example 1 - Flag is set

```xml
<FunctionalOption>
  <Name>UseWarehouse</Name>
  <PrivilegedModeOnGet>true</PrivilegedModeOnGet>
</FunctionalOption>
```

## 🔧 How to Fix

1. Open the functional option in Configurator
2. On the "Main" tab, enable "Privileged mode on get" flag

### Exception
Parameterized functional options for which the developer specifically provides different values for users with different access rights.

**Exception example:** 
Parameterized functional option `UseCurrencyForPayrollCalculations`, parameterized by organization. The user will not see the "currency" field in the document if they don't have any organization with currency accounting enabled.

## 🔍 Technical Details

- **Category**: Metadata checks
- **Applicability**: Functional options

## 📚 References

- [Standard #689 - Roles and Access Rights Configuration](https://its.1c.ru/db/v8std#content:689:hdoc:1.8)
