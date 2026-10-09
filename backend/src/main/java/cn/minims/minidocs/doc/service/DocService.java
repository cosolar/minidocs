package cn.minims.minidocs.doc.service;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.doc.dto.DocDtos.DocContentVO;
import cn.minims.minidocs.doc.dto.DocDtos.DocNode;
import cn.minims.minidocs.doc.dto.DocDtos.DownloadPayload;
import cn.minims.minidocs.doc.dto.DocDtos.ImportResult;
import cn.minims.minidocs.doc.dto.DocDtos.PathChangeVO;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderContext;
import cn.minims.minidocs.markdown.model.MarkdownModels.RenderResult;
import cn.minims.minidocs.markdown.model.MarkdownModels.Variant;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档服务：知识库感知的文件操作（权限、缓存、分享联动）。
 *
 * <p>权限一律通过 {@code AccessService.requireKb(kbId, 动作, user)} 判定，本接口的
 * {@code user} 参数只表示「以谁的身份」，不表示「归属者」。</p>
 */
public interface DocService {

    /** 目录树（需可读权限）。 */
    List<DocNode> tree(Long kbId, LoginUser user);

    /** 读取原文（需可读权限）。 */
    DocContentVO read(Long kbId, String path, LoginUser user);

    /** 渲染为 HTML + 大纲（需可读权限）。 */
    RenderResult render(Long kbId, String path, LoginUser user, Variant variant, RenderContext context);

    PathChangeVO createDoc(Long kbId, String path, String content, LoginUser user);

    PathChangeVO createDirectory(Long kbId, String path, LoginUser user);

    /**
     * 覆盖保存（规范 §4.3）。两道闸门，顺序不能反：
     *
     * <ol>
     *   <li>编辑锁：本人未持有有效锁 → 403，他人正在持有 → 409；</li>
     *   <li>基线比对：{@code baseSize} / {@code baseMtime} 取自由 {@code read} 回传的同名字段，
     *       与磁盘当前值不符即 412 —— 它兜住的是「锁过期后另一人接手」这段窗口。</li>
     * </ol>
     */
    PathChangeVO saveDoc(Long kbId, String path, String content, Long baseSize, LocalDateTime baseMtime,
                         LoginUser user);

    void delete(Long kbId, String path, LoginUser user);

    PathChangeVO rename(Long kbId, String path, String name, LoginUser user);

    PathChangeVO move(Long kbId, String path, String targetDir, LoginUser user);

    /**
     * 同目录内自定义排序：把 {@code path} 排到 {@code targetPath} 之前/之后。
     *
     * <p>跨目录的意图是移动，走 {@link #move}；这里只处理同一目录内的相对位置。</p>
     */
    void reorder(Long kbId, String path, String targetPath, String position, LoginUser user);

    /** 上传图片到知识库 assets/，返回相对知识库根的路径。 */
    String uploadImage(Long kbId, MultipartFile file, LoginUser user);

    /** 上传封面图，返回相对知识库根的路径。 */
    String uploadCover(Long kbId, MultipartFile file, LoginUser user);

    /** 设置外链封面；传空串等价于 {@link #clearCover} */
    String setCoverUrl(Long kbId, String url, LoginUser user);

    /** 清空封面。外链是手输的，没有单独这个入口就没法从界面上撤掉粘错的地址 */
    void clearCover(Long kbId, LoginUser user);

    DownloadPayload download(Long kbId, String path, LoginUser user);

    /**
     * 导入 Markdown / ZIP。
     *
     * @param relativePathsJson 与 {@code files} 同序的相对路径 JSON 数组，用于还原子目录树；
     *                          为空数组或省略时按文件名平铺到 {@code targetDir}
     */
    ImportResult importFiles(Long kbId, String targetDir, MultipartFile[] files, String relativePathsJson,
                             LoginUser user);
}
