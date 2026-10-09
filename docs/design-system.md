# Design system and accessibility rules

Tokens live in `ios/Packages/UI/Sources/UI/design/Theme.swift`: ink `#101317` on dark, system backgrounds on light, lime `#C7FF4A` as accent
(lime is text only on dark; on light the accent text is ink). Components: `SheetShell`, `SettingsRow`/`SettingsGroup`, `EmptyState`, `DayBars`,
`InitialsAvatar`.

Rules for every new screen:

- **Fonts**: use text styles (`.body`, `.footnote`, `Theme.digits(...)`). A fixed `.system(size:)` is only for an icon inside a fixed frame; text that is
  fixed-size uses `@ScaledMetric`.
- **Line limits**: use `.adaptiveLineLimit(n)` (design/Accessibility.swift) instead of `.lineLimit(n)`: it lifts the limit at the accessibility sizes.
  Rows are `HStack`s that must still wrap at XXL; test with `fittingSize(..., category: .accessibilityExtraExtraExtraLarge)` (see `DesignSystemTests`).
- **Motion**: use `.motionAnimation(_:value:)` and `Motion.run { }`; they do nothing under Reduce Motion. For a `transition`, pair it with `.motionAnimation`.
- **VoiceOver**: a row is one element (`.accessibilityElement(children: .combine)`), an icon-only control has `.accessibilityLabel`, a decorative image is
  `.accessibilityHidden(true)`, a selected state uses `.isSelected`. Phone numbers are read digit by digit.
- **Contrast**: `Theme.textSecondary` and `Theme.textTertiary` reach 4.5:1 on background, sheet and raised surfaces in both styles
  (`testSecondaryAndTertiaryTextReadAtFourPointFiveOnEverySurface`). Never put information in colour alone.
- **Touch targets**: at least 44 x 44 pt.
