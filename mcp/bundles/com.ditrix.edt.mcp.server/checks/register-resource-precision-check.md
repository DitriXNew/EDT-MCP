# Accumulation or accounting register resource length

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `register-resource-precision` |
| **Severity** | Minor |
| **Type** | Warning |

## 🎯 What This Check Does

Verifies that the accumulation or accounting register resource length does not exceed 25 digits. This limitation is related to calculation precision in the platform.

## ❌ Error Examples

### Example 1 - Resource too long

```xml
<AccumulationRegister>
  <Name>BalanceOfGoods</Name>
  <Resource>
    <Name>Quantity</Name>
    <Type>
      <NumberType>
        <Precision>30</Precision>  <!-- ERROR: More than 25 -->
        <Scale>5</Scale>
      </NumberType>
    </Type>
  </Resource>
</AccumulationRegister>
```

### Example 2 - Maximum length exceeded

```xml
<AccountingRegister>
  <Name>Accounting</Name>
  <Resource>
    <Name>Amount</Name>
    <Type>
      <NumberType>
        <Precision>31</Precision>  <!-- ERROR: Maximum exceeded -->
        <Scale>2</Scale>
      </NumberType>
    </Type>
  </Resource>
</AccountingRegister>
```

## ✅ Compliant Solutions

### Example 1 - Correct resource length

```xml
<AccumulationRegister>
  <Name>BalanceOfGoods</Name>
  <Resource>
    <Name>Quantity</Name>
    <Type>
      <NumberType>
        <Precision>15</Precision>  <!-- OK: Less than 25 -->
        <Scale>3</Scale>
      </NumberType>
    </Type>
  </Resource>
</AccumulationRegister>
```

### Example 2 - Maximum allowed length

```xml
<AccountingRegister>
  <Name>Accounting</Name>
  <Resource>
    <Name>Amount</Name>
    <Type>
      <NumberType>
        <Precision>25</Precision>  <!-- OK: Exactly 25 -->
        <Scale>2</Scale>
      </NumberType>
    </Type>
  </Resource>
</AccountingRegister>
```

## 🔧 How to Fix

1. Open the register resource in the Designer
2. Reduce the numeric field length to 25 or less

### Recommendations:
- For quantity resources: 15 digits
- For amount resources: 17 digits
- Maximum for register resource: 25 digits

## 🔍 Technical Details

- **Category**: Metadata checks
- **Applicability**: 
  - Accumulation registers
  - Accounting registers

## 📚 References

- [Numeric data in 1C](https://its.1c.ru/db/v8std)
