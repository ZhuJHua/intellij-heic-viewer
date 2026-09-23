// Re-encodes an image as HEIC with the given EXIF orientation (Apple stores it as irot/imir + EXIF).
// Build: swiftc -O -o writeorient writeorient.swift
// Usage: ./writeorient <input> <output.heic> <orientation 1-8>     (e.g. rgb_sips.heic exif6_apple.heic 6)
import Foundation
import ImageIO
import UniformTypeIdentifiers
let args = CommandLine.arguments
let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: args[1]) as CFURL, nil)!
let img = CGImageSourceCreateImageAtIndex(src, 0, nil)!
let orient = Int(args[3])!
let dst = CGImageDestinationCreateWithURL(URL(fileURLWithPath: args[2]) as CFURL, UTType.heic.identifier as CFString, 1, nil)!
let props: [CFString: Any] = [kCGImagePropertyOrientation: orient, kCGImageDestinationLossyCompressionQuality: 0.9]
CGImageDestinationAddImage(dst, img, props as CFDictionary)
print(CGImageDestinationFinalize(dst))
