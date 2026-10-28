// Draws a synthetic till receipt as a JPEG, for testing the reader without a real receipt: a real
// one carries a card's digits, a store's address and a household's shopping, and this repo is
// public. The text is made up; the layout follows a Canadian supermarket's till.
//
// Usage: swift scripts/draw-receipt.swift <receipt.txt> <out.jpg> [degrees]
// The optional angle tilts the paper, as a photo taken by hand would.
import AppKit

let args = CommandLine.arguments
guard args.count >= 3 else {
    FileHandle.standardError.write("usage: draw-receipt.swift <receipt.txt> <out.jpg> [degrees]\n".data(using: .utf8)!)
    exit(2)
}
let text = try String(contentsOfFile: args[1], encoding: .utf8)
let degrees = args.count > 3 ? Double(args[3]) ?? 0 : 0

let font = NSFont(name: "Menlo", size: 22) ?? NSFont.monospacedSystemFont(ofSize: 22, weight: .regular)
let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor(white: 0.12, alpha: 1)]
let body = NSAttributedString(string: text, attributes: attributes)
let textSize = body.boundingRect(with: NSSize(width: 2000, height: 10000), options: [.usesLineFragmentOrigin]).size

let paper = NSSize(width: ceil(textSize.width) + 80, height: ceil(textSize.height) + 120)
let canvas = NSSize(width: paper.width + 160, height: paper.height + 160)

let image = NSImage(size: canvas)
image.lockFocus()
// A table under the paper, so the edges show as they do in a photo.
NSColor(calibratedRed: 0.55, green: 0.50, blue: 0.45, alpha: 1).setFill()
NSRect(origin: .zero, size: canvas).fill()

let transform = NSAffineTransform()
transform.translateX(by: canvas.width / 2, yBy: canvas.height / 2)
transform.rotate(byDegrees: CGFloat(degrees))
transform.translateX(by: -paper.width / 2, yBy: -paper.height / 2)
transform.concat()

NSColor(white: 0.97, alpha: 1).setFill()
NSRect(origin: .zero, size: paper).fill()
body.draw(in: NSRect(x: 40, y: 60, width: textSize.width + 1, height: textSize.height))
image.unlockFocus()

guard let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff),
      let jpeg = bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.8]) else {
    FileHandle.standardError.write("could not encode the image\n".data(using: .utf8)!)
    exit(1)
}
try jpeg.write(to: URL(fileURLWithPath: args[2]))
print("wrote \(args[2]): \(Int(canvas.width))x\(Int(canvas.height))")
