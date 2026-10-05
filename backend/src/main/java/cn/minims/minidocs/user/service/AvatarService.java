package cn.minims.minidocs.user.service;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.FileNameUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.config.properties.MiniDocsProperties;
import cn.minims.minidocs.doc.support.ImageValidator;
import cn.minims.minidocs.reader.service.AssetService;
import cn.minims.minidocs.user.dto.UserDtos.UserVO;
import cn.minims.minidocs.user.entity.User;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 账号头像：校验、落盘、清理与读取。
 *
 * <p>头像存在 {@code VAULT_HOME/avatars} 下，文件名形如 {@code u{userId}-{时间戳}{随机}.{扩展名}}：
 * 用户 id 用于「换头像时清掉本人的旧图」，时间戳让 URL 随内容一起变，浏览器不必再做缓存失效判断。
 * 库表里只存文件名，对外地址由 {@link UserVO#AVATAR_PREFIX} 拼出 —— 换部署目录时不必洗库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private final MiniDocsProperties properties;
    private final ImageValidator imageValidator;
    private final UserService userService;
    private final AssetService assetService;

    /** 保存头像并写回 {@code users.avatar_url}，返回更新后的用户信息（含可直接放进 img src 的地址）。 */
    public UserVO save(Long userId, MultipartFile file) {
        String extension = imageValidator.validate(file, properties.getAvatarMaxSize());
        String fileName = "u" + userId + "-" + FileNameUtil.buildImageName(extension);
        write(properties.avatarsDir().resolve(fileName), file, extension);
        // 先落新图再删旧图：中途失败最坏是留个孤儿文件，不会把用户头像弄没
        deleteFilesOf(userId, fileName);
        updateColumn(userId, fileName);
        return UserVO.from(userService.getById(userId));
    }

    /** 清空头像：删文件并把列置 null，返回更新后的用户信息。 */
    public UserVO remove(Long userId) {
        deleteFilesOf(userId, null);
        updateColumn(userId, null);
        return UserVO.from(userService.getById(userId));
    }

    /** 读取头像文件；类型、大小、ETag 与缓存交给 {@link AssetService} 统一判定。 */
    public AssetService.Asset load(String fileName) {
        return assetService.load(properties.avatarsDir(), fileName);
    }

    private void write(Path target, MultipartFile file, String extension) {
        try {
            Files.createDirectories(target.getParent());
            byte[] content = file.getBytes();
            if ("svg".equals(extension)) {
                content = imageValidator.sanitizeSvg(content);
            }
            Files.write(target, content);
        } catch (IOException e) {
            throw BizException.of(ErrorCode.SERVER_ERROR, "头像保存失败");
        }
    }

    /** 删掉该用户除 {@code keep} 之外的头像文件。 */
    private void deleteFilesOf(Long userId, String keep) {
        Path dir = properties.avatarsDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        // 前缀带「-」，故 u1- 不会误伤 u11- 的文件
        String prefix = "u" + userId + "-";
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> stale = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> !path.getFileName().toString().equals(keep))
                    .toList();
            for (Path path : stale) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // 旧图没删掉只是占点磁盘，不该让「换头像」这件事失败
            log.warn("清理旧头像失败 userId={}", userId, e);
        }
    }

    private void updateColumn(Long userId, String fileName) {
        userService.update(Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getAvatarUrl, fileName)
                .set(User::getUpdatedAt, TimeUtil.now()));
    }
}
