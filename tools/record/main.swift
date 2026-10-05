// Records one window plus its app's audio to a .mov with ScreenCaptureKit (macOS 15+).
// Usage: swift record.swift <out.mov> <seconds> <window title substring> [go-flag file]
// When a go-flag path is given, the file is created once capture has started, so a scripted run
// (the Vellum dev tour) can wait for the recording before it begins.
import AppKit
import CoreMedia
import Foundation
import ScreenCaptureKit

// A command-line tool has no window-server connection until AppKit sets one up; capture asserts without it.
_ = NSApplication.shared

let args = CommandLine.arguments
guard args.count >= 4, let seconds = Double(args[2]) else {
    print("usage: record.swift <out.mov> <seconds> <window title substring> [go-flag file]")
    exit(2)
}
let out = URL(fileURLWithPath: args[1])
let match = args[3]
try? FileManager.default.removeItem(at: out)

let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true)
guard let window = content.windows.first(where: { ($0.title ?? "").contains(match) }) else {
    print("no window titled *\(match)*; on-screen windows:")
    for w in content.windows where (w.title ?? "") != "" {
        print("  \(w.owningApplication?.applicationName ?? "?"): \(w.title ?? "")")
    }
    exit(1)
}

let config = SCStreamConfiguration()
let scale = 2.0 // Retina backing pixels
config.width = Int(window.frame.width * scale)
config.height = Int(window.frame.height * scale)
config.minimumFrameInterval = CMTime(value: 1, timescale: 60)
config.showsCursor = false
config.capturesAudio = true
config.sampleRate = 48_000
config.channelCount = 2

final class Recording: NSObject, SCRecordingOutputDelegate {
    func recordingOutput(_ output: SCRecordingOutput, didFailWithError error: any Error) {
        print("recording failed: \(error)")
    }
}
let delegate = Recording()
let recordingConfig = SCRecordingOutputConfiguration()
recordingConfig.outputURL = out
recordingConfig.outputFileType = .mov
recordingConfig.videoCodecType = .h264

let stream = SCStream(filter: SCContentFilter(desktopIndependentWindow: window), configuration: config, delegate: nil)
try stream.addRecordingOutput(SCRecordingOutput(configuration: recordingConfig, delegate: delegate))
try await stream.startCapture()
print("recording \(window.title ?? "") at \(config.width)x\(config.height) for \(seconds)s")
if args.count >= 5 { FileManager.default.createFile(atPath: args[4], contents: nil) }
try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
try await stream.stopCapture()
try await Task.sleep(nanoseconds: 500_000_000) // let the file finish
print("saved \(out.path)")
