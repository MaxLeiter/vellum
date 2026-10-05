// Records one window, with the system's audio, to a .mov file:
//   record <out.mov> <seconds> <title substring> [go-file]
// The go-file is created once capture has started, so a scripted client (the dev tour) can wait for it.
// Frames and audio come from ScreenCaptureKit and are written with AVAssetWriter (H.264 and AAC).
import AVFoundation
import AppKit
import ScreenCaptureKit

_ = NSApplication.shared // ScreenCaptureKit needs a connection to the window server

let args = CommandLine.arguments
guard args.count >= 4, let seconds = Double(args[2]) else {
    print("usage: record <out.mov> <seconds> <title substring> [go-file]")
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

let filter = SCContentFilter(desktopIndependentWindow: window)
let scale = Double(filter.pointPixelScale)
let config = SCStreamConfiguration()
// The window's backing pixels in whole 16 px blocks, which the H.264 encoder takes at any size.
config.width = Int(filter.contentRect.width * scale) / 16 * 16
config.height = Int(filter.contentRect.height * scale) / 16 * 16
config.scalesToFit = true
config.minimumFrameInterval = CMTime(value: 1, timescale: 60)
config.queueDepth = 8
config.showsCursor = false
config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange // what the H.264 encoder takes directly
let withAudio = ProcessInfo.processInfo.environment["RECORD_NO_AUDIO"] == nil
config.capturesAudio = withAudio
config.sampleRate = 48_000
config.channelCount = 2

let writer = try AVAssetWriter(outputURL: out, fileType: .mov)
let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: config.width,
    AVVideoHeightKey: config.height,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 24_000_000, AVVideoExpectedSourceFrameRateKey: 60],
])
video.expectsMediaDataInRealTime = true
let audio = AVAssetWriterInput(mediaType: .audio, outputSettings: [
    AVFormatIDKey: kAudioFormatMPEG4AAC,
    AVSampleRateKey: 48_000,
    AVNumberOfChannelsKey: 2,
    AVEncoderBitRateKey: 192_000,
])
audio.expectsMediaDataInRealTime = true
writer.add(video)
if withAudio { writer.add(audio) }
guard writer.startWriting() else {
    print("cannot write \(out.path): \(String(describing: writer.error))")
    exit(1)
}

/** Appends complete frames and the audio after the first frame; the session starts at the first frame's time. */
final class Sink: NSObject, SCStreamOutput, SCStreamDelegate {
    private var started = false
    private(set) var frames = 0
    private(set) var screenBuffers = 0, audioBuffers = 0, incomplete = 0

    func stream(_ stream: SCStream, didOutputSampleBuffer buffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard buffer.isValid else { return }
        switch type {
        case .screen:
            screenBuffers += 1
            guard let info = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
                  let raw = info.first?[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete else {
                incomplete += 1
                if incomplete <= 3 {
                    let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: false) as? [[AnyHashable: Any]]
                    print("not complete: \(String(describing: attachments?.first?[SCStreamFrameInfo.status.rawValue]))")
                }
                return
            }
            if !started {
                writer.startSession(atSourceTime: buffer.presentationTimeStamp)
                started = true
            }
            if video.isReadyForMoreMediaData {
                if video.append(buffer) { frames += 1 } else if frames == 0 { print("video append failed: \(String(describing: writer.error))") }
            }
        case .audio:
            audioBuffers += 1
            if started, audio.isReadyForMoreMediaData, !audio.append(buffer) { print("audio append failed: \(String(describing: writer.error))") }
        default:
            break
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: any Error) {
        print("capture stopped: \(error)")
    }
}

let sink = Sink()
let queue = DispatchQueue(label: "record")
let stream = SCStream(filter: filter, configuration: config, delegate: sink)
try stream.addStreamOutput(sink, type: .screen, sampleHandlerQueue: queue)
if withAudio { try stream.addStreamOutput(sink, type: .audio, sampleHandlerQueue: queue) }
try await stream.startCapture()
print("recording \(window.title ?? "") at \(config.width)x\(config.height) for \(seconds)s")
if args.count >= 5 { FileManager.default.createFile(atPath: args[4], contents: nil) }
try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
try await stream.stopCapture()
queue.sync {
    video.markAsFinished()
    if withAudio { audio.markAsFinished() }
}
print("buffers: \(sink.screenBuffers) screen (\(sink.incomplete) not complete), \(sink.audioBuffers) audio, \(sink.frames) frames written")
await writer.finishWriting()
if writer.status == .completed {
    print("saved \(out.path) (\(sink.frames) frames)")
} else {
    print("failed: \(String(describing: writer.error))")
    exit(1)
}
