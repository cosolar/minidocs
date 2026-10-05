package cn.minims.minidocs.doc.support;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 图片上传校验：扩展名 + MIME + 文件头魔数三者一致，并对 SVG 做净化。
 */
@Component
@RequiredArgsConstructor
public class ImageValidator {

    private static final int HEAD_LENGTH = 512;
    private static final String SVG_EXTERNAL_PROTOCOL = "http";

    private final MiniDocsProperties properties;

    /**
     * 校验并返回小写扩展名。
     */
    public String validate(MultipartFile file, long maxSize) {
        if (file == null || file.isEmpty()) {
            throw BizException.param("上传文件为空");
        }
        String originalName = file.getOriginalFilename();
        String extension = FileNameUtil.extensionOf(originalName == null ? "" : originalName);
        if (!MiniDocsProperties.IMAGE_EXTENSIONS.contains(extension)) {
            throw BizException.of(ErrorCode.FILE_TYPE_NOT_ALLOWED, "不支持的图片类型：" + extension);
        }
        if (file.getSize() > maxSize) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE,
                    "图片超过 " + (maxSize / 1024 / 1024) + "MB 限制");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw BizException.of(ErrorCode.FILE_TYPE_NOT_ALLOWED, "MIME 类型与图片不符：" + contentType);
        }
        byte[] head = readHead(file);
        if (!magicMatches(extension, head)) {
            throw BizException.of(ErrorCode.FILE_TYPE_NOT_ALLOWED, "文件内容与扩展名不符");
        }
        return extension;
    }

    public String validate(MultipartFile file) {
        return validate(file, properties.getImageMaxSize());
    }

    private byte[] readHead(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(HEAD_LENGTH);
        } catch (IOException e) {
            throw BizException.param("读取上传文件失败");
        }
    }

    boolean magicMatches(String extension, byte[] head) {
        return switch (extension) {
            case "png" -> startsWith(head, new int[]{0x89, 0x50, 0x4E, 0x47});
            case "jpg", "jpeg" -> startsWith(head, new int[]{0xFF, 0xD8, 0xFF});
            case "gif" -> head.length >= 6 && ("GIF87a".equals(ascii(head, 0, 6)) || "GIF89a".equals(ascii(head, 0, 6)));
            case "webp" -> head.length >= 12 && "RIFF".equals(ascii(head, 0, 4)) && "WEBP".equals(ascii(head, 8, 4));
            case "bmp" -> startsWith(head, new int[]{0x42, 0x4D});
            case "ico" -> startsWith(head, new int[]{0x00, 0x00, 0x01, 0x00});
            case "avif" -> head.length >= 12 && "ftyp".equals(ascii(head, 4, 4))
                    && ascii(head, 8, 4).startsWith("avi");
            case "svg" -> {
                String text = new String(head, StandardCharsets.UTF_8);
                yield text.contains("<svg") || text.contains("<?xml");
            }
            default -> false;
        };
    }

    /**
     * SVG 净化：移除脚本、事件属性、foreignObject 与外部引用。
     */
    public byte[] sanitizeSvg(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        Document document = Jsoup.parse(text, "", Parser.xmlParser());
        document.outputSettings().prettyPrint(false);
        document.select("script, foreignObject, iframe, object, embed, use").remove();

        for (Element element : document.getAllElements()) {
            List<Attribute> attributes = new ArrayList<>(element.attributes().asList());
            for (Attribute attribute : attributes) {
                String key = attribute.getKey().toLowerCase(Locale.ROOT);
                String value = attribute.getValue() == null ? "" : attribute.getValue().trim().toLowerCase(Locale.ROOT);
                if (key.startsWith("on")) {
                    element.removeAttr(attribute.getKey());
                    continue;
                }
                if ("href".equals(key) || "xlink:href".equals(key) || "src".equals(key)) {
                    if (value.startsWith(SVG_EXTERNAL_PROTOCOL) || value.startsWith("//") || value.startsWith("javascript:")) {
                        element.removeAttr(attribute.getKey());
                    }
                    continue;
                }
                if ("style".equals(key) && value.contains("javascript:")) {
                    element.removeAttr(attribute.getKey());
                }
            }
        }
        return document.outerHtml().getBytes(StandardCharsets.UTF_8);
    }

    private boolean startsWith(byte[] head, int[] magic) {
        if (head.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((head[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private String ascii(byte[] head, int offset, int length) {
        if (head.length < offset + length) {
            return "";
        }
        return new String(head, offset, length, StandardCharsets.US_ASCII);
    }
}
