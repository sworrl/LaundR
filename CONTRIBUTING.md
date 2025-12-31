# Contributing to LaundR

Thank you for your interest in contributing to the LaundR project! Community contributions help expand our understanding of laundry card systems and improve detection capabilities.

---

## Contributing Card Dumps (.nfc Files)

We welcome contributions of .nfc card dumps to help:
- Identify new laundry card operators and their data formats
- Improve automatic provider detection
- Build a comprehensive database of card structures
- Create reference materials for security research

### What We Need

**Card dumps from different operators:**
- CSC Service Works (various regions)
- Heartland Payment Systems
- WASH Multifamily
- Coinmach / CSC ServiceWorks
- Mac-Gray
- Caldwell & Gregory
- Any other laundry card providers

**Multiple cards from the same operator:**
- Cards with different balance amounts
- Cards before and after transactions
- New cards vs. heavily used cards
- Cards from different geographic regions

### How to Contribute

#### Option 1: GitHub Pull Request (Preferred)

1. Fork this repository
2. Create a new branch: `git checkout -b add-operator-name-cards`
3. Add your .nfc files to `test_cards/contributed/`
4. Include a text file with card details (see format below)
5. Submit a pull request

#### Option 2: GitHub Issue

1. Open a new issue titled "Card Contribution: [Operator Name]"
2. Attach your .nfc file(s)
3. Include the card details in the issue body

#### Option 3: Email

Contact the maintainers directly (see repository for contact info)

---

## Card Submission Format

When submitting cards, please include as much of the following information as possible:

```
Operator Name: [e.g., CSC Service Works]
Geographic Region: [e.g., California, USA]
Card Type: [e.g., Laundry, Vending, Parking]
Known Balance: [e.g., $5.25 - if you know it]
Date Captured: [e.g., 2025-01-15]
Capture Method: [e.g., Flipper Zero, Proxmark3, ACR122U]
Additional Notes: [Any other relevant information]
```

### Example Submission

```
Operator Name: WASH Multifamily Laundry
Geographic Region: Texas, USA
Card Type: Apartment laundry room
Known Balance: $12.50
Date Captured: 2025-01-10
Capture Method: Flipper Zero (Official firmware)
Additional Notes: Card purchased from machine, used twice for $3.00 washes
```

---

## Privacy and Legal Requirements

### Before You Submit

**You MUST ensure:**

1. **You own the card** - Only submit dumps of cards you legally purchased or own
2. **No personal data** - Verify the dump doesn't contain personally identifiable information
3. **Legal acquisition** - The card was obtained through legitimate means (purchased, given to you, etc.)
4. **Authorization** - If the card belongs to someone else, you have their explicit permission

### What We Do NOT Accept

- Cards obtained without authorization
- Stolen or found cards
- Cards with known personal data embedded
- Dumps from active/in-use cards you don't own
- Any submissions that appear to facilitate fraud

### Sanitization (Optional but Appreciated)

If your card contains any identifying information, consider:
- Zeroing out blocks that might contain personal data
- Noting which blocks you've modified
- Providing both original and sanitized versions

---

## Code Contributions

We also welcome code contributions:

### Areas of Interest

- **New operator detection** - Signature patterns for unrecognized providers
- **Decoding algorithms** - Better interpretation of balance/counter formats
- **Flipper app improvements** - Enhanced emulation and logging
- **Documentation** - Clearer explanations, more examples
- **Bug fixes** - Issues with parsing, display, or calculation

### Development Guidelines

1. Follow existing code style
2. Test with multiple card types
3. Document new features
4. Update relevant docs/

### Pull Request Process

1. Fork and create a feature branch
2. Make your changes
3. Test thoroughly
4. Submit PR with clear description
5. Respond to review feedback

---

## Building the Master Key Database

One goal of this project is to build a comprehensive database of:
- MIFARE keys used by laundry operators
- Block structure patterns for each provider
- Balance encoding schemes
- Transaction counter formats

### How You Can Help

**Key Discovery:**
If you've successfully extracted keys from a card (using legitimate methods like known-plaintext attacks on your own card), we'd love to add them to our dictionary.

**Structure Documentation:**
If you've mapped out the block structure for a new operator, document it and submit!

**Validation:**
Test our existing detection against your cards and report accuracy.

---

## Recognition

Contributors will be acknowledged in:
- The project README (if desired)
- Release notes when their contributions ship
- The CONTRIBUTORS.md file

Let us know your preferred attribution (GitHub username, name, or anonymous).

---

## Questions?

Open a GitHub issue with the "question" label or reach out to maintainers.

---

**Thank you for helping advance RFID security research!**

*Remember: All contributions must be legally obtained and ethically sourced.*
