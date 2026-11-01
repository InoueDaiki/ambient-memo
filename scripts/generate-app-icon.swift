// アプリのアイコンを apps/mobile/assets に書き出す。形や色を変えるときに実行する。
// 使い方: swift scripts/generate-app-icon.swift apps/mobile/assets
import AppKit

let size = 1024
let outDir = CommandLine.arguments[1]

struct Palette {
    let background: NSColor
    let sheet: NSColor
    let bars: NSColor
}

func hex(_ value: UInt32) -> NSColor {
    NSColor(
        srgbRed: CGFloat((value >> 16) & 0xFF) / 255,
        green: CGFloat((value >> 8) & 0xFF) / 255,
        blue: CGFloat(value & 0xFF) / 255,
        alpha: 1
    )
}

let production = Palette(background: hex(0x3F6B4F), sheet: hex(0xF8F6F1), bars: hex(0x3F6B4F))
let development = Palette(background: hex(0xC2701E), sheet: hex(0xF8F6F1), bars: hex(0xA85C14))

// メモの紙と波形。アダプティブアイコンの安全域に収まるよう、中央の約 6 割に描く
func drawMark(_ context: CGContext, sheet: NSColor, bars: NSColor, scale: CGFloat, punchOut: Bool = false) {
    let center = CGFloat(size) / 2
    let width = 440 * scale
    let height = 520 * scale
    let rect = CGRect(x: center - width / 2, y: center - height / 2, width: width, height: height)
    context.setFillColor(sheet.cgColor)
    context.addPath(CGPath(roundedRect: rect, cornerWidth: 56 * scale, cornerHeight: 56 * scale, transform: nil))
    context.fillPath()

    // 波形が下の 2 行の文字へ落ち着く形にする
    let heights: [CGFloat] = [90, 190, 280, 150, 230, 110]
    let barWidth = 38 * scale
    let gap = 26 * scale
    let total = CGFloat(heights.count) * barWidth + CGFloat(heights.count - 1) * gap
    let waveCenterY = center + 76 * scale
    if punchOut { context.setBlendMode(.clear) }
    context.setFillColor(bars.cgColor)
    for (index, height) in heights.enumerated() {
        let x = center - total / 2 + CGFloat(index) * (barWidth + gap)
        let barHeight = height * scale
        let bar = CGRect(x: x, y: waveCenterY - barHeight / 2, width: barWidth, height: barHeight)
        context.addPath(CGPath(roundedRect: bar, cornerWidth: barWidth / 2, cornerHeight: barWidth / 2, transform: nil))
    }
    context.fillPath()

    let lines: [(y: CGFloat, width: CGFloat)] = [(-128, 1), (-186, 0.62)]
    for line in lines {
        let lineRect = CGRect(x: center - total / 2, y: center + line.y * scale, width: total * line.width, height: 26 * scale)
        context.addPath(CGPath(roundedRect: lineRect, cornerWidth: 13 * scale, cornerHeight: 13 * scale, transform: nil))
    }
    context.fillPath()
}

func render(_ name: String, background: NSColor?, draw: (CGContext) -> Void) {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
        samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
        bytesPerRow: 0, bitsPerPixel: 0
    )!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let context = NSGraphicsContext.current!.cgContext
    if let background {
        context.setFillColor(background.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: size, height: size))
    }
    draw(context)
    NSGraphicsContext.restoreGraphicsState()
    let data = rep.representation(using: .png, properties: [:])!
    try! data.write(to: URL(fileURLWithPath: "\(outDir)/\(name).png"))
}

for (suffix, palette) in [("", production), ("-dev", development)] {
    render("icon\(suffix)", background: palette.background) { context in
        drawMark(context, sheet: palette.sheet, bars: palette.bars, scale: 1.3)
    }
    render("adaptive-foreground\(suffix)", background: nil) { context in
        drawMark(context, sheet: palette.sheet, bars: palette.bars, scale: 0.92)
    }
}
// テーマアイコン用。OS が色を付けるので、紙を抜いた形にする
render("adaptive-monochrome", background: nil) { context in
    drawMark(context, sheet: .white, bars: .white, scale: 0.92, punchOut: true)
}
