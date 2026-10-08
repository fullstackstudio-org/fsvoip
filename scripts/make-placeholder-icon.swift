// SPDX-License-Identifier: AGPL-3.0-or-later
// Draws the PLACEHOLDER app icon (lime square, dark handset). The real icon (an SVG design in the FullStack
// Studio style) replaces it later. The App Store wants an icon WITHOUT alpha: strip it with
//   sips -s format jpeg X.png --out X.jpg && sips -s format png X.jpg --out X.png && rm X.jpg
// Usage: swift scripts/make-placeholder-icon.swift ios/FSVoip/Assets.xcassets/AppIcon.appiconset/AppIcon.png
import AppKit

let size = 1024
let output = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.png"
let lime = NSColor(srgbRed: 199 / 255, green: 255 / 255, blue: 74 / 255, alpha: 1)
let ink = NSColor(srgbRed: 16 / 255, green: 19 / 255, blue: 23 / 255, alpha: 1)

guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: size * 4, bitsPerPixel: 32) else { exit(1) }
rep.size = NSSize(width: size, height: size)

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
lime.setFill()
NSRect(x: 0, y: 0, width: size, height: size).fill()

let configuration = NSImage.SymbolConfiguration(pointSize: 560, weight: .bold).applying(.init(paletteColors: [ink]))
if let symbol = NSImage(systemSymbolName: "phone.fill", accessibilityDescription: nil)?.withSymbolConfiguration(configuration) {
    let rect = NSRect(x: (CGFloat(size) - symbol.size.width) / 2, y: (CGFloat(size) - symbol.size.height) / 2, width: symbol.size.width, height: symbol.size.height)
    symbol.draw(in: rect)
}
NSGraphicsContext.restoreGraphicsState()

try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: output))
