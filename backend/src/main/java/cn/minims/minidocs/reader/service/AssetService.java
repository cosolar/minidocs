package cn.minims.minidocs.reader.service;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.PathGuard;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 图片资源代理：门户 / 阅读页 / 分享页 / 后台预览共用。
 *
 * <p>仅允许图片扩展名；路径解析后必须落在知识库根目录内；单文件 ≤ 20MB；
 * 响应带 ETag 与 1 天强缓存；SVG 附加严格 CSP。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("avif", "image/avif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("ico", "image/x-icon"));

    private static final String SVG_CSP = "default-src 'none'; style-src 'unsafe-inline'";

    private final MiniDocsProperties properties;

    public record Asset(Resource resource, String contentType, long length, String etag, boolean svg) {
    }

    /** 加载并校验图片资源。 */
    public Asset load(Path kbRoot, String relative) {
        String normalized = PathGuard.normalizeRelative(relative);
        if (normalized.isEmpty() || !FileNameUtil.isImage(normalized)) {
            throw BizException.notFound("资源不存在");
        }
        Path file = PathGuard.resolve(kbRoot, normalized);
        if (!Files.isRegularFile(file)) {
            throw BizException.notFound("资源不存在");
        }
        long size;
        long modified;
        try {
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw BizException.notFound("资源不存在");
        }
        if (size > properties.getImageMaxSize()) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE, "图片超过 20MB 限制");
        }
        String extension = FileNameUtil.extensionOf(normalized);
        String contentType = CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
        String etag = "W/\"" + Long.toString(modified, 36) + "-" + Long.toString(size, 36) + "\"";
        return new Asset(new FileSystemResource(file), contentType, size, etag, "svg".equals(extension));
    }

    /** 组装响应，支持 If-None-Match 协商缓存。 */
    public ResponseEntity<Resource> respond(Asset asset, String ifNoneMatch) {
        if (asset.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(asset.etag())
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                    .build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(asset.contentType()));
        headers.setContentLength(asset.length());
        headers.setETag(asset.etag());
        headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic());
        if (asset.svg()) {
            headers.set("Content-Security-Policy", SVG_CSP);
        }
        return new ResponseEntity<>(asset.resource(), headers, HttpStatus.OK);
    }
}
