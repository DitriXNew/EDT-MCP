# Right set: View Event Log

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-view-event-log` |
| **Category** | Role Rights |
| **Severity** | Major |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `ViewEventLog` right in roles. The event log contains information about user actions and system events. Access should be limited to administrative roles.

## ❌ Error Examples

```xml
<!-- Incorrect - ViewEventLog for regular user -->
<Rights>
  <Right>
    <Name>ViewEventLog</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - All users can view log -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>ViewEventLog</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - ViewEventLog for Administrator -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>ViewEventLog</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Auditor role for compliance -->
<!-- Role: Auditor -->
<Rights>
  <Right>
    <Name>ViewEventLog</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Regular users cannot view log -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>ViewEventLog</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the ViewEventLog right for user roles
2. Keep the right for Administrator and Auditor roles
3. The event log contains confidential information
4. Create special reports for limited log access

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightViewEventLog`
- **Plugin**: `com.e1c.v8codestyle.right`

