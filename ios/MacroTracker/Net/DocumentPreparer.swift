import Compression
import Foundation

/// Turns an uploaded file into Gemini request parts (inline PDF/image, or extracted text).
enum DocumentPreparer {
    /// Gemini accepts up to ~20 MB per request; leave room for base64 overhead.
    static let maxBytes = 12 * 1024 * 1024
    private static let maxTextChars = 200_000
    private static let inlineImageTypes: Set<String> = ["image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"]
    private static let textExtensions: Set<String> = ["txt", "csv", "md", "json", "xml", "html", "htm", "tsv"]

    static func prepare(_ data: Data, mimeType: String?, fileName: String) throws -> [AIPart] {
        if data.isEmpty { throw AIError(message: "El archivo está vacío") }
        if data.count > maxBytes { throw AIError(message: "El archivo supera \(maxBytes / (1024 * 1024)) MB") }
        let ext = (fileName as NSString).pathExtension.lowercased()
        let mime = (mimeType ?? "").lowercased().components(separatedBy: ";").first?.trimmingCharacters(in: .whitespaces) ?? ""
        let header = AIPart.text("Documento adjunto: \(fileName.isEmpty ? "sin nombre" : fileName)")

        if mime == "application/pdf" || ext == "pdf" {
            return [header, .inline("application/pdf", data)]
        }
        if inlineImageTypes.contains(mime) {
            return [header, .inline(mime, data)]
        }
        if mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || ext == "docx" {
            return [header, try textPart(try docxText(data))]
        }
        if mime.hasPrefix("text/") || textExtensions.contains(ext) {
            return [header, try textPart(String(decoding: data, as: UTF8.self))]
        }
        throw AIError(message: "Formato no soportado. Usa PDF, imagen, Word (.docx) o texto.")
    }

    private static func textPart(_ text: String) throws -> AIPart {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { throw AIError(message: "No se encontró texto en el documento") }
        return .text(String(clean.prefix(maxTextChars)))
    }

    /// Reads the paragraphs of word/document.xml.
    static func docxText(_ data: Data) throws -> String {
        guard let xmlData = ZipReader.entry(named: "word/document.xml", in: data) else {
            throw AIError(message: "El archivo Word no es válido")
        }
        var xml = String(decoding: xmlData, as: UTF8.self)
        xml = xml.replacingOccurrences(of: "</w:p>", with: "\n")
        xml = xml.replacingOccurrences(of: "<w:tab\\s*/>", with: "\t", options: .regularExpression)
        xml = xml.replacingOccurrences(of: "<w:br\\s*/>", with: "\n", options: .regularExpression)
        xml = xml.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        for (entity, char) in [("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&apos;", "'"), ("&amp;", "&")] {
            xml = xml.replacingOccurrences(of: entity, with: char)
        }
        let lines = xml.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
        return lines.joined(separator: "\n")
            .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Just enough of the ZIP format to read one file out of a .docx.
enum ZipReader {
    static func entry(named name: String, in data: Data) -> Data? {
        let bytes = [UInt8](data)
        func u16(_ i: Int) -> Int { i + 1 < bytes.count ? Int(bytes[i]) | Int(bytes[i + 1]) << 8 : 0 }
        func u32(_ i: Int) -> Int { i + 3 < bytes.count ? u16(i) | u16(i + 2) << 16 : 0 }

        // End of central directory record: signature 0x06054b50, searched from the end.
        var eocd = -1
        var i = bytes.count - 22
        while i >= max(0, bytes.count - 22 - 65_535) {
            if u32(i) == 0x0605_4b50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { return nil }
        let count = u16(eocd + 10)
        var offset = u32(eocd + 16)

        for _ in 0..<count {
            guard u32(offset) == 0x0201_4b50 else { return nil }
            let method = u16(offset + 10)
            let compressedSize = u32(offset + 20)
            let size = u32(offset + 24)
            let nameLength = u16(offset + 28)
            let extraLength = u16(offset + 30)
            let commentLength = u16(offset + 32)
            let localHeader = u32(offset + 42)
            let entryName = String(decoding: bytes[(offset + 46)..<min(offset + 46 + nameLength, bytes.count)], as: UTF8.self)
            offset += 46 + nameLength + extraLength + commentLength
            guard entryName == name, u32(localHeader) == 0x0403_4b50 else { continue }

            let start = localHeader + 30 + u16(localHeader + 26) + u16(localHeader + 28)
            guard start + compressedSize <= bytes.count else { return nil }
            let payload = Array(bytes[start..<(start + compressedSize)])
            switch method {
            case 0: return Data(payload)
            case 8: return inflate(payload, size: size)
            default: return nil
            }
        }
        return nil
    }

    /// Raw DEFLATE (what ZIP uses), decoded with Apple's Compression framework.
    private static func inflate(_ input: [UInt8], size: Int) -> Data? {
        guard size > 0 else { return Data() }
        guard !input.isEmpty else { return nil }
        var output = [UInt8](repeating: 0, count: size)
        let written = input.withUnsafeBufferPointer { src in
            output.withUnsafeMutableBufferPointer { dst in
                compression_decode_buffer(dst.baseAddress!, size, src.baseAddress!, input.count, nil, COMPRESSION_ZLIB)
            }
        }
        return written == size ? Data(output) : nil
    }
}
