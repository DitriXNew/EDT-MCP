# Right set: Administration

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-administration` |
| **Category** | Role Rights |
| **Severity** | Critical |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `Administration` right in roles. This is one of the most important system rights, providing access to administrative functions: user management, backup, configuration updates. Should only be available for the Administrator role.

## ❌ Error Examples

```xml
<!-- Incorrect - Administration right for non-admin role -->
<Rights>
  <Right>
    <Name>Administration</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - Multiple roles with Administration -->
<!-- Role: Manager -->
<Rights>
  <Right>
    <Name>Administration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - Administration only for Administrator role -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>Administration</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Other roles without Administration -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>Administration</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Review all roles with the Administration right
2. Remove the right for all roles except Administrator
3. Create separate roles for specific administrative tasks
4. Use the DataAdministration right for data management

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightAdministration`
- **Plugin**: `com.e1c.v8codestyle.right`

## 📚 References

- [Role-based access restriction](https://its.1c.ru/db/v8std/content/689/hdoc)
