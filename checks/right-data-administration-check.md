# Right set: Data Administration

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-data-administration` |
| **Category** | Role Rights |
| **Severity** | Critical |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `DataAdministration` right in roles. This right allows performing data operations: deleting marked objects, restructuring, testing and fixing. Should be limited to administrative roles.

## ❌ Error Examples

```xml
<!-- Incorrect - DataAdministration for regular user -->
<Rights>
  <Right>
    <Name>DataAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - Manager with data administration -->
<!-- Role: Manager -->
<Rights>
  <Right>
    <Name>DataAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - DataAdministration for Administrator only -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>DataAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Regular roles without DataAdministration -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>DataAdministration</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the DataAdministration right for user roles
2. Keep the right only for the Administrator role
3. Use scheduled jobs for automatic operations
4. Document data administration operations

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightDataAdministration`
- **Plugin**: `com.e1c.v8codestyle.right`

