# Right set: Start Thin Client

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-start-thin-client` |
| **Category** | Role Rights |
| **Severity** | Minor |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `StartThinClient` right in roles. Thin client is the recommended operating mode for most users. Checks the correct configuration of the right in accordance with client usage policy.

## ❌ Error Examples

```xml
<!-- Potentially incorrect - ThinClient disabled without reason -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>StartThinClient</Name>
    <Value>false</Value>
  </Right>
</Rights>

<!-- Incorrect - Inconsistent client settings -->
<!-- Role: Operator -->
<Rights>
  <Right>
    <Name>StartThinClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartThickClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartWebClient</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - ThinClient enabled for regular users -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>StartThinClient</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Web-only users -->
<!-- Role: ExternalUser -->
<Rights>
  <Right>
    <Name>StartThinClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartWebClient</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Full access for administrator -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>StartThinClient</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Allow StartThinClient for most roles
2. Ensure the role has at least one connection method
3. Use thin client as the primary mode
4. Document the client usage policy

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightStartThinClient`
- **Plugin**: `com.e1c.v8codestyle.right`

