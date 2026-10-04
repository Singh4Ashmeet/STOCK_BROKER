//! Symbol parsing and interning

use core::fmt;
use core::str::FromStr;

use crate::error::CoreError;

/// Maximum lengths for symbol components
const MAX_VENUE_LEN: usize = 16;
const MAX_ASSET_LEN: usize = 20;

/// Parsed symbol components (borrowed, no allocation).
///
/// Format: `VENUE:BASE-QUOTE`
/// - VENUE: 1..=16 uppercase ASCII letters/digits
/// - BASE: 1..=20 uppercase ASCII letters/digits
/// - QUOTE: 1..=20 uppercase ASCII letters/digits
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SymbolParts<'a> {
    /// Venue name (e.g., "BINANCE")
    pub venue: &'a str,
    /// Base asset (e.g., "BTC")
    pub base: &'a str,
    /// Quote asset (e.g., "USDT")
    pub quote: &'a str,
}

/// Parse a symbol string into its components.
///
/// # Errors
///
/// Returns `CoreError::InvalidSymbol` with a static message for:
/// - Missing colon separator
/// - Missing dash separator
/// - Empty venue, base, or quote
/// - Venue longer than 16 chars
/// - Base or quote longer than 20 chars
/// - Non-ASCII or non-alphanumeric characters
/// - Lowercase letters
///
/// # Examples
///
/// ```
/// use sb_core::parse_symbol;
///
/// let parts = parse_symbol("BINANCE:BTC-USDT").unwrap();
/// assert_eq!(parts.venue, "BINANCE");
/// assert_eq!(parts.base, "BTC");
/// assert_eq!(parts.quote, "USDT");
/// ```
pub fn parse_symbol(s: &str) -> Result<SymbolParts<'_>, CoreError> {
    // Find the colon separating venue from the rest
    let Some(colon_pos) = s.find(':') else {
        return Err(CoreError::InvalidSymbol("missing colon separator"));
    };

    let venue = &s[..colon_pos];
    let rest = &s[colon_pos + 1..];

    // Find the dash separating base from quote
    let Some(dash_pos) = rest.find('-') else {
        return Err(CoreError::InvalidSymbol("missing dash separator"));
    };

    let base = &rest[..dash_pos];
    let quote = &rest[dash_pos + 1..];

    // Validate non-empty
    if venue.is_empty() || base.is_empty() || quote.is_empty() {
        return Err(CoreError::InvalidSymbol("empty component"));
    }

    // Validate lengths
    if venue.len() > MAX_VENUE_LEN {
        return Err(CoreError::InvalidSymbol("venue too long"));
    }
    if base.len() > MAX_ASSET_LEN {
        return Err(CoreError::InvalidSymbol("base asset too long"));
    }
    if quote.len() > MAX_ASSET_LEN {
        return Err(CoreError::InvalidSymbol("quote asset too long"));
    }

    // Validate characters: uppercase ASCII letters and digits only
    fn is_valid(s: &str) -> bool {
        s.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
    }

    if !is_valid(venue) || !is_valid(base) || !is_valid(quote) {
        return Err(CoreError::InvalidSymbol("invalid characters (must be uppercase ASCII alnum)"));
    }

    Ok(SymbolParts { venue, base, quote })
}

/// Opaque symbol ID for hot-path use.
///
/// Use `SymbolTable` to convert between strings and IDs.
/// `Copy`, `Eq`, `Hash`, `Ord` - suitable for map keys and hot paths.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct SymbolId(u32);

impl SymbolId {
    /// Create from raw ID (for testing/fuzzing).
    #[must_use]
    pub const fn new(id: u32) -> Self {
        Self(id)
    }

    /// Get the raw ID.
    #[must_use]
    pub fn id(self) -> u32 {
        self.0
    }
}

impl fmt::Display for SymbolId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "SymbolId({})", self.0)
    }
}

/// Startup-time symbol registry.
///
/// `SymbolTable` assigns incrementing IDs to symbols.
/// - `intern`: validates and stores the symbol, returns its ID (same name = same ID)
/// - `lookup`: find ID by name (O(n) for small N, use `HashMap` if needed)
/// - `name`: get name by ID
///
/// IDs are assigned in insertion order starting from 0.
/// Not thread-safe; use externally synchronized or only at startup.
#[derive(Debug, Default)]
pub struct SymbolTable {
    names: Vec<String>,
}

impl SymbolTable {
    /// Create a new empty symbol table.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Intern a symbol string, returning its ID.
    ///
    /// Validates the symbol format. Same name always returns the same ID.
    /// Errors if symbol table would exceed `u32::MAX` entries (practically impossible).
    ///
    /// # Errors
    ///
    /// Returns `CoreError::InvalidSymbol` for format errors.
    /// Returns `CoreError::SymbolTableFull` if table is full (`u32::MAX` entries).
    pub fn intern(&mut self, s: &str) -> Result<SymbolId, CoreError> {
        // Validate first
        parse_symbol(s)?;

        // Check if already exists
        for (i, name) in self.names.iter().enumerate() {
            if name == s {
                #[allow(clippy::unwrap_used)]
                return Ok(SymbolId(u32::try_from(i).unwrap()));
            }
        }

        // Insert new
        let id = self.names.len();
        if id > u32::MAX as usize {
            return Err(CoreError::SymbolTableFull);
        }
        self.names.push(s.to_string());
        #[allow(clippy::unwrap_used)]
        Ok(SymbolId(u32::try_from(id).unwrap()))
    }

    /// Look up the ID for a symbol string (O(n)).
    #[must_use]
    pub fn lookup(&self, s: &str) -> Option<SymbolId> {
        self.names.iter().position(|n| n == s).map(|i| {
            #[allow(clippy::unwrap_used)]
            SymbolId(u32::try_from(i).unwrap())
        })
    }

    /// Get the symbol name for an ID.
    #[must_use]
    pub fn name(&self, id: SymbolId) -> Option<&str> {
        self.names.get(id.0 as usize).map(String::as_str)
    }

    /// Number of symbols in the table.
    #[must_use]
    pub fn len(&self) -> usize {
        self.names.len()
    }

    /// Check if table is empty.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.names.is_empty()
    }
}

impl FromStr for SymbolId {
    type Err = CoreError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        // This is a fallback; in practice use SymbolTable::intern
        parse_symbol(s)?;
        // We can't assign an ID without a table, so this is just for parsing
        Err(CoreError::InvalidSymbol("use SymbolTable::intern for ID assignment"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_valid_symbol() {
        let parts = parse_symbol("BINANCE:BTC-USDT").unwrap();
        assert_eq!(parts.venue, "BINANCE");
        assert_eq!(parts.base, "BTC");
        assert_eq!(parts.quote, "USDT");

        let parts = parse_symbol("BYBIT:ETH-USD").unwrap();
        assert_eq!(parts.venue, "BYBIT");
        assert_eq!(parts.base, "ETH");
        assert_eq!(parts.quote, "USD");
    }

    #[test]
    fn parse_invalid_symbol() {
        // Missing colon
        assert!(parse_symbol("BINANCE-BTC-USDT").is_err());
        // Missing dash
        assert!(parse_symbol("BINANCE:BTCUSDT").is_err());
        // Empty component
        assert!(parse_symbol("BINANCE:-USDT").is_err());
        assert!(parse_symbol("BINANCE:BTC-").is_err());
        assert!(parse_symbol(":BTC-USDT").is_err());
        // Too long
        assert!(parse_symbol(&("B".repeat(17) + ":BTC-USDT")).is_err());
        assert!(parse_symbol(&("BINANCE:".to_owned() + &"B".repeat(21) + "-USDT")).is_err());
        assert!(parse_symbol(&("BINANCE:BTC-".to_owned() + &"Q".repeat(21))).is_err());
        // Invalid characters
        assert!(parse_symbol("binance:BTC-USDT").is_err()); // lowercase
        assert!(parse_symbol("BINANCE:BTC_USDT").is_err()); // underscore
        assert!(parse_symbol("BINANCE:BTC.USDT").is_err()); // dot
        // Non-ASCII
        assert!(parse_symbol("BINANCE:BTC-USDŤ").is_err());
    }

    #[test]
    fn symbol_table_intern() {
        let mut table = SymbolTable::new();
        let id1 = table.intern("BINANCE:BTC-USDT").unwrap();
        let id2 = table.intern("BINANCE:BTC-USDT").unwrap();
        assert_eq!(id1, id2); // Same ID for same name

        let id3 = table.intern("BYBIT:ETH-USD").unwrap();
        assert_ne!(id1, id3);

        assert_eq!(table.name(id1), Some("BINANCE:BTC-USDT"));
        assert_eq!(table.name(id3), Some("BYBIT:ETH-USD"));
        assert_eq!(table.len(), 2);
    }

    #[test]
    fn symbol_table_lookup() {
        let mut table = SymbolTable::new();
        let id = table.intern("BINANCE:BTC-USDT").unwrap();
        assert_eq!(table.lookup("BINANCE:BTC-USDT"), Some(id));
        assert_eq!(table.lookup("BYBIT:ETH-USD"), None);
    }

    #[test]
    fn symbol_table_invalid() {
        let mut table = SymbolTable::new();
        assert!(table.intern("invalid").is_err());
    }
}
