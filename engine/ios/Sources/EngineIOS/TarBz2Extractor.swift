import CBzip2
import CryptoKit
import EngineDomain
import Foundation

private let BUFFER_BYTES = 1 << 16
private let TAR_BLOCK = 512

/// 展開の書き込みに失敗した。読み取りの失敗(配布物の破損)と分ける。
struct ExtractWriteError: Error {
    let cause: any Error
}

/// 配布物の bzip2 が壊れている。
struct Bzip2Error: Error {}

/**
 * tar.bz2 から必要なファイルだけを取り出す。Android の TarBz2Extractor と同じ決まりにする。
 *
 * 名前がカタログと一致するファイルだけを書く。配布物の中のパスは書き込み先に使わない。
 * [onRead] は配布物を読んだバイト数の累計を受け取る。取り消しは [Task.checkCancellation] で確かめる。
 */
struct TarBz2Extractor {
    let onRead: (Int64) -> Void

    /// [directory] の下の [files] を、[archive] から [target] へ取り出す。
    func extract(_ files: [ModelFile], directory: String, archive: URL, target: URL) throws {
        var wanted = Dictionary(uniqueKeysWithValues: files.map { ("\(directory)/\($0.name)", $0) })
        do {
            let reader = try Bzip2Reader(archive, onRead: onRead)
            defer { reader.close() }
            try extractEntries(TarReader(reader), &wanted, target)
        } catch let error as ModelDownloadException {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as ExtractWriteError {
            throw storageFailure(usableBytes(target), error.cause)
        } catch {
            throw ModelDownloadException(.CORRUPTED, cause: error)
        }
    }

    /// 必要なファイルがすべて見つかったら、残りは読まない。
    private func extractEntries(_ tar: TarReader, _ wanted: inout [String: ModelFile], _ target: URL) throws {
        while !wanted.isEmpty {
            guard let entry = try tar.next() else { throw ModelDownloadException(.CORRUPTED) }
            let name = entry.name.hasPrefix("./") ? String(entry.name.dropFirst(2)) : entry.name
            if entry.isFile, let file = wanted.removeValue(forKey: name) {
                try extractFile(tar, entry, file, target.appendingPathComponent(file.name))
            } else {
                try tar.skip(entry.size)
            }
        }
    }

    private func extractFile(_ tar: TarReader, _ entry: TarEntry, _ expected: ModelFile, _ target: URL) throws {
        guard entry.size == expected.sizeBytes else { throw ModelDownloadException(.CORRUPTED) }
        guard FileManager.default.createFile(atPath: target.path, contents: nil) else {
            throw ExtractWriteError(cause: ModelTransportError(description: "ファイルを作れません"))
        }
        let output: FileHandle
        do { output = try FileHandle(forWritingTo: target) } catch { throw ExtractWriteError(cause: error) }
        defer { try? output.close() }
        var hasher = SHA256()
        var remaining = entry.size
        while remaining > 0 {
            try Task.checkCancellation()
            let chunk = try tar.read(Int(min(Int64(BUFFER_BYTES), remaining)))
            if chunk.isEmpty { throw ModelDownloadException(.CORRUPTED) }
            hasher.update(data: chunk)
            do { try output.write(contentsOf: chunk) } catch { throw ExtractWriteError(cause: error) }
            remaining -= Int64(chunk.count)
        }
        try tar.finishEntry(entry.size)
        if hexString(hasher.finalize()) != expected.sha256 { throw ModelDownloadException(.CORRUPTED) }
    }
}

struct TarEntry {
    let name: String
    let size: Int64
    let isFile: Bool
}

/// tar の見出しを読む。ustar の prefix、GNU の長い名前(L)、pax の path を扱う。
final class TarReader {
    private let input: Bzip2Reader

    init(_ input: Bzip2Reader) {
        self.input = input
    }

    /// 次のファイルの見出しを返す。終わりなら nil。
    func next() throws -> TarEntry? {
        var overrideName: String?
        while true {
            let header = try readExactly(TAR_BLOCK)
            if header.allSatisfy({ $0 == 0 }) { return nil }
            let size = try Self.size(header)
            let type = header[156]
            switch type {
            case UInt8(ascii: "L"):
                // GNU の長い名前。次の見出しの名前になる
                overrideName = Self.string(try readExactly(Int(size)), 0, Int(size))
                try finishEntry(size)
            case UInt8(ascii: "x"):
                let records = try readExactly(Int(size))
                try finishEntry(size)
                if let path = Self.paxPath(records) { overrideName = path }
            case UInt8(ascii: "g"):
                try skip(size)
            default:
                var name = Self.string(header, 0, 100)
                let magic = Self.string(header, 257, 5)
                if magic == "ustar" {
                    let prefix = Self.string(header, 345, 155)
                    if !prefix.isEmpty { name = "\(prefix)/\(name)" }
                }
                let isFile = type == 0 || type == UInt8(ascii: "0") || type == UInt8(ascii: "7")
                return TarEntry(name: overrideName ?? name, size: size, isFile: isFile)
            }
        }
    }

    func read(_ count: Int) throws -> Data { try input.read(count) }

    /// 本文を読み飛ばす。大きな不要なファイルの読み飛ばしも中断できるように、少しずつ読む。
    func skip(_ size: Int64) throws {
        var remaining = size
        while remaining > 0 {
            try Task.checkCancellation()
            let chunk = try input.read(Int(min(Int64(BUFFER_BYTES), remaining)))
            if chunk.isEmpty { throw Bzip2Error() }
            remaining -= Int64(chunk.count)
        }
        try finishEntry(size)
    }

    /// 本文の後ろの、512 バイトに揃える詰め物を読む。
    func finishEntry(_ size: Int64) throws {
        let padding = (Int64(TAR_BLOCK) - size % Int64(TAR_BLOCK)) % Int64(TAR_BLOCK)
        if padding > 0 { _ = try readExactly(Int(padding)) }
    }

    private func readExactly(_ count: Int) throws -> [UInt8] {
        var bytes: [UInt8] = []
        bytes.reserveCapacity(count)
        while bytes.count < count {
            let chunk = try input.read(count - bytes.count)
            if chunk.isEmpty { throw Bzip2Error() }
            bytes.append(contentsOf: chunk)
        }
        return bytes
    }

    private static func string(_ bytes: [UInt8], _ offset: Int, _ length: Int) -> String {
        let slice = bytes[offset..<min(offset + length, bytes.count)]
        let end = slice.firstIndex(of: 0) ?? slice.endIndex
        return String(decoding: slice[slice.startIndex..<end], as: UTF8.self)
    }

    /// 8 進数か、先頭の bit が立った 256 進数。
    private static func size(_ header: [UInt8]) throws -> Int64 {
        let field = header[124..<136]
        if let first = field.first, first & 0x80 != 0 {
            var value: Int64 = 0
            for (index, byte) in field.enumerated() {
                value = (value << 8) | Int64(index == 0 ? byte & 0x7F : byte)
            }
            return value
        }
        let text = String(decoding: field.prefix { $0 != 0 && $0 != 0x20 }, as: UTF8.self)
            .trimmingCharacters(in: .whitespaces)
        if text.isEmpty { return 0 }
        guard let value = Int64(text, radix: 8) else { throw Bzip2Error() }
        return value
    }

    /// pax の記録 `<長さ> path=<名前>\n` から名前を読む。
    private static func paxPath(_ records: [UInt8]) -> String? {
        let text = String(decoding: records, as: UTF8.self)
        for line in text.split(separator: "\n") {
            guard let space = line.firstIndex(of: " ") else { continue }
            let record = line[line.index(after: space)...]
            if record.hasPrefix("path=") { return String(record.dropFirst("path=".count)) }
        }
        return nil
    }
}

/// libbz2 で読む。連結された bzip2 も続けて読む(Android の commons-compress の `decompressConcatenated` と同じ)。
final class Bzip2Reader {
    private let handle: FileHandle
    private let onRead: (Int64) -> Void
    private var stream = bz_stream()
    private let inBuffer = UnsafeMutablePointer<CChar>.allocate(capacity: BUFFER_BYTES)
    private let outBuffer = UnsafeMutablePointer<CChar>.allocate(capacity: BUFFER_BYTES)
    private var pending = Data()
    private var inputEnded = false
    private var finished = false
    private var isOpen = false
    private var compressedRead: Int64 = 0

    init(_ url: URL, onRead: @escaping (Int64) -> Void) throws {
        handle = try FileHandle(forReadingFrom: url)
        self.onRead = onRead
        try begin()
    }

    private func begin() throws {
        let nextIn = stream.next_in
        let availIn = stream.avail_in
        stream = bz_stream()
        guard BZ2_bzDecompressInit(&stream, 0, 0) == BZ_OK else { throw Bzip2Error() }
        stream.next_in = nextIn
        stream.avail_in = availIn
        isOpen = true
    }

    private func end() {
        if isOpen { BZ2_bzDecompressEnd(&stream) }
        isOpen = false
    }

    private func refill() throws {
        let chunk = try handle.read(upToCount: BUFFER_BYTES) ?? Data()
        if chunk.isEmpty {
            inputEnded = true
            return
        }
        chunk.copyBytes(to: UnsafeMutableRawPointer(inBuffer).assumingMemoryBound(to: UInt8.self), count: chunk.count)
        stream.next_in = inBuffer
        stream.avail_in = UInt32(chunk.count)
        compressedRead += Int64(chunk.count)
        onRead(compressedRead)
    }

    /// 最大 [count] バイトを返す。終わりなら空。
    func read(_ count: Int) throws -> Data {
        while pending.isEmpty, !finished { try decompressMore() }
        let taken = pending.prefix(count)
        pending.removeFirst(taken.count)
        return Data(taken)
    }

    private func decompressMore() throws {
        if stream.avail_in == 0, !inputEnded { try refill() }
        stream.next_out = outBuffer
        stream.avail_out = UInt32(BUFFER_BYTES)
        let status = BZ2_bzDecompress(&stream)
        let produced = BUFFER_BYTES - Int(stream.avail_out)
        if produced > 0 { pending.append(Data(bytes: outBuffer, count: produced)) }
        switch status {
        case BZ_STREAM_END:
            end()
            if stream.avail_in == 0, !inputEnded { try refill() }
            if stream.avail_in == 0, inputEnded {
                finished = true
            } else {
                try begin()
            }
        case BZ_OK:
            if produced == 0, stream.avail_in == 0, inputEnded { throw Bzip2Error() }
        default:
            throw Bzip2Error()
        }
    }

    func close() {
        end()
        try? handle.close()
        inBuffer.deallocate()
        outBuffer.deallocate()
    }
}

/// 書き込みに失敗したときに、空き容量がこれを切っていれば容量不足とみなす。
private let FULL_DISK_BYTES: Int64 = 1 * 1024 * 1024

func storageFailure(_ usableBytes: Int64, _ cause: any Error) -> ModelDownloadException {
    let failure: ModelDownloadFailure = usableBytes < FULL_DISK_BYTES ? .INSUFFICIENT_STORAGE : .UNKNOWN
    return ModelDownloadException(failure, cause: cause)
}

/// 書き込める空き容量。読めなければ 0 とみなす。
func usableBytes(_ url: URL) -> Int64 {
    let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
    return values?.volumeAvailableCapacityForImportantUsage ?? 0
}
