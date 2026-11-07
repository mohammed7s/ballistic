//! Exact number handling: JSON-RPC quantities and wei → ETH, in integer/decimal-string arithmetic.
//! No floating point anywhere; a 256-bit quantity converts without overflow.

/// Wei per ETH has 18 decimal places.
pub const ETH_DECIMALS: usize = 18;

/// A JSON-RPC QUANTITY: `0x` then hex digits, no leading zeros (`0x0` for zero), at most 64
/// digits (256 bits). Anything else is malformed; nothing is guessed or trimmed.
fn quantity_digits(hex: &str) -> Option<&str> {
    let digits = hex.strip_prefix("0x")?;
    let ok = !digits.is_empty()
        && digits.len() <= 64
        && digits.bytes().all(|b| b.is_ascii_hexdigit())
        && (digits == "0" || !digits.starts_with('0'));
    ok.then_some(digits)
}

/// The quantity as a decimal string (`"0x1a"` → `"26"`).
pub fn quantity_decimal(hex: &str) -> Option<String> {
    let digits = quantity_digits(hex)?;
    // Big-endian decimal digits; multiply by 16 and add each nibble.
    let mut dec: Vec<u8> = vec![0];
    for nibble in digits.bytes() {
        let mut carry = (nibble as char).to_digit(16)? as u32;
        for d in dec.iter_mut().rev() {
            let v = *d as u32 * 16 + carry;
            *d = (v % 10) as u8;
            carry = v / 10;
        }
        while carry > 0 {
            dec.insert(0, (carry % 10) as u8);
            carry /= 10;
        }
    }
    Some(dec.iter().map(|d| (b'0' + d) as char).collect())
}

/// The quantity as a u64 (block numbers, chain ids). `None` if malformed or too large.
pub fn quantity_u64(hex: &str) -> Option<u64> {
    let digits = quantity_digits(hex)?;
    if digits.len() > 16 {
        return None;
    }
    u64::from_str_radix(digits, 16).ok()
}

/// `n` as a JSON-RPC quantity.
pub fn to_quantity(n: u64) -> String {
    format!("0x{n:x}")
}

/// Canonical non-negative decimal integer: digits only, no sign, no leading zeros.
pub fn is_decimal(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()) && (s == "0" || !s.starts_with('0'))
}

/// Exact wei → ETH: `"1234500000000000000"` → `"1.2345"`, `"1"` → `"0.000000000000000001"`,
/// `"0"` → `"0"`. Trailing fractional zeros are dropped; nothing is rounded.
pub fn wei_to_eth(wei: &str) -> Option<String> {
    if !is_decimal(wei) {
        return None;
    }
    let padded = format!("{wei:0>width$}", width = ETH_DECIMALS + 1);
    let (whole, frac) = padded.split_at(padded.len() - ETH_DECIMALS);
    let frac = frac.trim_end_matches('0');
    Some(if frac.is_empty() { whole.to_string() } else { format!("{whole}.{frac}") })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_exact_amounts() {
        assert_eq!(wei_to_eth("0").unwrap(), "0");
        assert_eq!(wei_to_eth("1").unwrap(), "0.000000000000000001");
        assert_eq!(wei_to_eth("1000000000000000000").unwrap(), "1");
        assert_eq!(wei_to_eth("1234500000000000000").unwrap(), "1.2345");
        // 30 digits: 123456789012.345678901234567890 ETH, every digit kept.
        assert_eq!(wei_to_eth("123456789012345678901234567890").unwrap(), "123456789012.34567890123456789");
        assert_eq!(wei_to_eth("100000000000000000000").unwrap(), "100");
        for bad in ["", "01", "-1", "1.5", "1e18", " 1"] {
            assert!(wei_to_eth(bad).is_none(), "{bad:?}");
        }
    }

    #[test]
    fn parses_quantities_strictly() {
        assert_eq!(quantity_decimal("0x0").unwrap(), "0");
        assert_eq!(quantity_decimal("0x1").unwrap(), "1");
        assert_eq!(quantity_decimal("0xde0b6b3a7640000").unwrap(), "1000000000000000000");
        assert_eq!(quantity_decimal("0x18ee90ff6c373e0ee4e3f0ad2").unwrap(), "123456789012345678901234567890");
        let max = format!("0x{}", "f".repeat(64));
        assert_eq!(
            quantity_decimal(&max).unwrap(),
            "115792089237316195423570985008687907853269984665640564039457584007913129639935"
        );
        for bad in ["", "0x", "0x00", "0x01", "1a", "0xZZ", "0X1", "0x1 ", &format!("0x1{}", "0".repeat(64))] {
            assert!(quantity_decimal(bad).is_none(), "{bad:?}");
        }
        assert_eq!(quantity_u64("0x1").unwrap(), 1);
        assert!(quantity_u64("0x10000000000000000").is_none());
        assert_eq!(to_quantity(21_000_000), "0x1406f40");
    }
}
