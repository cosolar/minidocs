package cn.minims.minidocs.git;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.exception.BizException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 云端知识库的 Git 工作副本操作（纯 JGit，不依赖系统 git 命令）。
 *
 * <p>工作副本就是知识库目录本身（{@code vaults/{storageKey}}），所以目录树、渲染、导入导出
 * 这些下游能力一行都不用改 —— 它们只认 storage_key，不关心这份内容是从哪儿来的。</p>
 *
 * <p><b>冲突策略是「拒绝并提示」而不是自动合并。</b>拉取前若工作区不干净（含未跟踪的新文档）
 * 直接拒绝，让用户先提交推送；能快进就快进，一旦真的合并出冲突就中止并报错，绝不把
 * {@code <<<<<<<} 标记写进用户的 Markdown。宁可让人来手动处理，也不静默改写正文。</p>
 */
@Slf4j
@Component
public class GitVaultService {

    /** clone 的默认远端名，JGit 固定用 origin */
    private static final String REMOTE = "origin";
    /** 网络操作上限：远端无响应时不要挂住请求线程 */
    private static final int TIMEOUT_SECONDS = 120;
    /** 返回给用户的原因上限，JGit 的异常链可能很长 */
    private static final int MAX_REASON = 200;
    /**
     * 工作区状态的短 TTL 缓存，理由见 {@link #status}。
     *
     * <p>按仓库维度而不是全局失效：写入路径只知道自己的 repoDir，
     * 而一个库被改动不该让其他库的缓存作废（虽然代价也只是重扫一次，
     * 但缓存存在的意义就是不互相牵连）。上限给到 64 份，超了按 LRU 淘汰。</p>
     */
    private final Cache<String, RepoStatus> statusCache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build();

    // ------------------------------------------------------------------ 克隆

    /**
     * 克隆远程仓库到知识库目录。
     *
     * <p>目标目录必须不存在或为空：它是要成为工作副本的地方，里面有东西说明这个落点已经被占了，
     * 覆盖上去等于毁掉别人的内容。</p>
     *
     * @param branch 分支短名（main / master）；为空时用远端的默认分支
     */
    public void cloneRepository(String url, String branch, String username, String token, Path target) {
        if (Files.exists(target) && !isEmptyDir(target)) {
            throw BizException.of(ErrorCode.GIT_PARAM_INVALID, "目标目录已存在且不为空：" + target.getFileName());
        }
        try {
            Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(target.toFile())
                    .setCloneAllBranches(false)
                    .setBranch(branchRef(branch))
                    .setBranchesToClone(List.of(branchRef(branch)))
                    .setCredentialsProvider(credentials(username, token))
                    .setTimeout(TIMEOUT_SECONDS)
                    .call()
                    .close();
            log.info("克隆 Git 仓库完成：{} ({}) -> {}", url, branch, target);
            // 克隆前 status() 很可能已经缓存过「工作副本不存在」（RepoStatus.absent()），
            // 不清的话克隆完成后那 5s 内界面仍显示「未就绪」
            invalidateStatus(target);
        } catch (GitAPIException e) {
            deleteQuietly(target);
            throw BizException.of(ErrorCode.GIT_SYNC_FAILED, "克隆仓库失败：" + reasonOf(e));
        }
    }

    // ------------------------------------------------------------------ 拉取

    /**
     * 拉取远程更新到工作副本。
     *
     * @throws BizException 工作区有未提交改动（409）、合并冲突（409）、或网络/认证失败（502）
     */
    /**
     * 删掉工作区里的未跟踪文件与目录。
     *
     * <p><b>只在「强制覆盖」时调用</b>，且必须在 {@code reset --hard} 之后：
     * JGit 的 reset 不处理未跟踪文件，而作者新建的文档正是未跟踪的 —— 不清掉就会出现
     * 「工作区已经没有改动了，但那几篇新文档还躺在库里」的错觉。</p>
     *
     * <p>用JGit 的 {@code CleanCommand} 而不是递归删文件：它默认就跳过 {@code .gitignore}
     * 里的东西，不会把构建产物或本地配置一起删掉。</p>
     */
    private void cleanUntracked(Git git) throws Exception {
        git.clean().setCleanDirectories(true).call();
    }

    /**
     * 从远程拉取。
     *
     * @param force {@code true} = 丢弃本地未提交改动（hard reset）后再拉；{@code false} = 合并拉取，
     *              工作区脏时直接拒绝。默认走合并：JGit 的 {@code PullCommand} 只做合并，
     *              脏工作区下强行 pull 会把本地改动留在冲突标记里，那是最难收拾的一种状态。
     */
    public SyncOutcome pull(Path repoDir, String branch, String username, String token, boolean force) {
        try (Git git = open(repoDir)) {
            Repository repository = git.getRepository();
            Status status = git.status().call();
            if (!status.isClean()) {
                if (!force) {
                    // 不自动 stash：stash 之后一旦后续步骤失败，用户根本不知道自己的改动去了哪
                    // 计数走 changedPaths：getUncommittedChanges 不含未跟踪文件，新建的文档会被漏算成「0 处改动」
                    throw BizException.of(ErrorCode.GIT_DIRTY,
                            "本地有 " + changedPaths(status).size() + " 处未提交的改动，请先「提交并推送」再拉取，"
                                    + "或选择「强制覆盖本地」");
                }
                /*
                 * 强制覆盖 = hard reset 到 HEAD：丢弃已跟踪文件的改动，并删掉索引里已登记的改动。
                 * 未跟踪文件（JGit 的 reset 不动它们）单独清理，否则作者新建的文档会留在原地，
                 * 而他以为「已经覆盖了」。
                 */
                List<String> dropped = changedPaths(status);
                git.reset().setMode(ResetType.HARD).call();
                cleanUntracked(git);
                log.warn("强制覆盖本地改动 {} 个文件后拉取仓库 {}", dropped.size(), repoDir.getFileName());
            }
            ObjectId before = repository.resolve("HEAD");
            PullResult result = git.pull()
                    .setRemote(REMOTE)
                    .setRemoteBranchName(branch)
                    .setCredentialsProvider(credentials(username, token))
                    .setTimeout(TIMEOUT_SECONDS)
                    .call();
            if (!result.isSuccessful()) {
                throw BizException.of(ErrorCode.GIT_SYNC_FAILED, "拉取失败：" + describePull(result));
            }
            MergeResult merge = result.getMergeResult();
            if (merge != null && merge.getMergeStatus() == MergeStatus.CONFLICTING) {
                throw BizException.of(ErrorCode.GIT_CONFLICT, "远程更新与本地冲突，已中止合并，请人工处理");
            }
            if (merge != null && merge.getMergeStatus() == MergeStatus.FAILED) {
                throw BizException.of(ErrorCode.GIT_SYNC_FAILED, "合并失败，请检查本地与远程的分支关系");
            }
            ObjectId after = repository.resolve("HEAD");
            int commits = before == null || before.equals(after) ? 0 : countCommits(repository, before, after);
            String message = commits == 0 ? "已是最新，没有需要更新的提交"
                    : "已更新 " + commits + " 个提交到 " + shortId(after);
            log.info("拉取 Git 仓库 {}：{}", repoDir.getFileName(), message);
            invalidateStatus(repoDir);
            return new SyncOutcome(true, message, commits, shortId(after));
        } catch (BizException e) {
            invalidateStatus(repoDir);
            throw e;
        } catch (Exception e) {
            invalidateStatus(repoDir);
            throw BizException.of(ErrorCode.GIT_SYNC_FAILED, "拉取失败：" + reasonOf(e));
        }
    }

    // ------------------------------------------------------------------ 提交并推送

    /**
     * 把工作副本的改动提交并推送到远程。
     *
     * <p>没有改动时不报错而是照样推一次：本地可能只是「已提交但上次推送失败」，那种情况
     * 用户点这个按钮的意图就是把它推上去。</p>
     */
    public SyncOutcome commitAndPush(Path repoDir, String branch, String username, String token,
                                     String authorName, String authorEmail, String message) {
        try (Git git = open(repoDir)) {
            Status status = git.status().call();
            List<String> changed = changedPaths(status);
            String commitId = null;
            if (changed.isEmpty()) {
                log.info("工作副本无改动，仅尝试推送：{}", repoDir.getFileName());
            } else {
                // add 两次：第二次带 update 才能把「删除」也放进暂存区，只 add(".") 会漏掉删除
                git.add().addFilepattern(".").call();
                git.add().setUpdate(true).addFilepattern(".").call();
                RevCommit commit = git.commit()
                        .setMessage(commitMessage(message, changed))
                        .setAuthor(authorName, authorEmail)
                        .setCommitter(authorName, authorEmail)
                        .call();
                commitId = shortId(commit.getId());
            }
            push(git, branch, username, token);
            String summary = changed.isEmpty() ? "没有新的改动，已确认远程为最新"
                    : "已提交并推送 " + changed.size() + " 个文件（" + commitId + "）";
            log.info("提交并推送 Git 仓库 {}：{}", repoDir.getFileName(), summary);
            invalidateStatus(repoDir);
            return new SyncOutcome(true, summary, changed.size(), commitId);
        } catch (BizException e) {
            invalidateStatus(repoDir);
            throw e;
        } catch (Exception e) {
            invalidateStatus(repoDir);
            throw BizException.of(ErrorCode.GIT_SYNC_FAILED, "提交推送失败：" + reasonOf(e));
        }
    }

    private void push(Git git, String branch, String username, String token) throws GitAPIException {
        Iterable<PushResult> results = git.push()
                .setRemote(REMOTE)
                .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                .setCredentialsProvider(credentials(username, token))
                .setTimeout(TIMEOUT_SECONDS)
                .call();
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                RemoteRefUpdate.Status state = update.getStatus();
                if (state != RemoteRefUpdate.Status.OK && state != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw BizException.of(ErrorCode.GIT_SYNC_FAILED,
                            "推送被拒绝（" + state + "），远程可能已有新提交，请先拉取");
                }
            }
        }
    }

    // ------------------------------------------------------------------ 状态

    /** 工作副本状态快照。目录不是 Git 仓库时返回 {@link RepoStatus#absent()}。 */
    /**
     * 工作副本状态。
     *
     * <p><b>带短 TTL 缓存</b>：{@link Status#call()} 要把整个工作区跟索引逐个比对，
     * 189 篇的库实测 260ms，冷缓存时能到 4s —— 而这个接口是「打开工作区就调一次」，
     * 克隆期间的前端轮询更是每 3s 调一次。全是重复扫同一份磁盘，没有一次结果会不一样。
     *
     * <p>TTL 取 5s：够短到「保存文档后胶囊不会骗人」，够长到把一次打开工作区里的
     * 重复调用、以及轮询期的连续调用合并掉。写入路径（pull / commit）主动失效，
     * 所以真正会改变状态的操作读到的永远是新值。</p>
     */
    public RepoStatus status(Path repoDir, String branch) {
        String key = repoDir + "@" + (branch == null ? "" : branch);
        RepoStatus cached = statusCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        RepoStatus computed = computeStatus(repoDir, branch);
        statusCache.put(key, computed);
        return computed;
    }

    /**
     * 状态变更后清缓存。pull / commit / push 都会改工作区或索引，
     * 不清的话胶囊会在 TTL 内继续显示旧数字，而用户刚点的按钮理应立刻反映在界面上。
     */
    public void invalidateStatus(Path repoDir) {
        statusCache.invalidateAll();
    }

    private RepoStatus computeStatus(Path repoDir, String branch) {
        if (!Files.isDirectory(repoDir.resolve(".git"))) {
            return RepoStatus.absent();
        }
        try (Git git = open(repoDir)) {
            Repository repository = git.getRepository();
            Status status = git.status().call();
            List<String> changed = changedPaths(status);
            int ahead = 0;
            int behind = 0;
            BranchTrackingStatus tracking = BranchTrackingStatus.of(repository, branch);
            if (tracking != null) {
                ahead = tracking.getAheadCount();
                behind = tracking.getBehindCount();
            }
            return new RepoStatus(true, status.isClean(), changed.size(), ahead, behind,
                    shortId(repository.resolve("HEAD")), changed);
        } catch (Exception e) {
            log.warn("读取 Git 状态失败：{} - {}", repoDir, e.getMessage());
            return RepoStatus.absent();
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private Git open(Path repoDir) throws IOException {
        return Git.open(repoDir.toFile());
    }

    /**
     * 凭据。用户名与令牌都没填时不挂 credentialsProvider —— 公开仓库无需认证，
     * 而给 JGit 一个空凭据反而会让它去走一次注定失败的认证。
     */
    private UsernamePasswordCredentialsProvider credentials(String username, String token) {
        boolean hasUser = username != null && !username.isBlank();
        boolean hasToken = token != null && !token.isBlank();
        if (!hasUser && !hasToken) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(hasUser ? username : "", hasToken ? token : "");
    }

    private static String branchRef(String branch) {
        String name = branch == null || branch.isBlank() ? "main" : branch.trim();
        return name.startsWith("refs/") ? name : "refs/heads/" + name;
    }

    private static List<String> changedPaths(Status status) {
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(status.getAdded());
        paths.addAll(status.getChanged());
        paths.addAll(status.getModified());
        paths.addAll(status.getRemoved());
        paths.addAll(status.getMissing());
        paths.addAll(status.getUntracked());
        paths.addAll(status.getConflicting());
        return new ArrayList<>(paths);
    }

    /** 提交信息：用户在界面上写的那句 + 改动清单（清单进正文，方便在 git log 里看清这批改了什么）。 */
    private static String commitMessage(String message, List<String> changed) {
        String title = message == null || message.isBlank() ? "更新知识库内容" : message.trim();
        StringBuilder builder = new StringBuilder(title);
        builder.append("\n\n");
        int limit = Math.min(changed.size(), 50);
        for (int i = 0; i < limit; i++) {
            builder.append("- ").append(changed.get(i)).append('\n');
        }
        if (changed.size() > limit) {
            builder.append("- ...（共 ").append(changed.size()).append(" 个文件）\n");
        }
        return builder.toString();
    }

    private static int countCommits(Repository repository, ObjectId from, ObjectId to) {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(to));
            if (from != null) {
                walk.markUninteresting(walk.parseCommit(from));
            }
            int count = 0;
            for (RevCommit ignored : walk) {
                count++;
            }
            return count;
        } catch (IOException e) {
            log.warn("统计提交数失败：{}", e.getMessage());
            return 0;
        }
    }

    private static String describePull(PullResult result) {
        if (result.getMergeResult() != null) {
            return String.valueOf(result.getMergeResult().getMergeStatus());
        }
        return "未知原因";
    }

    private static String shortId(ObjectId id) {
        return id == null ? null : id.abbreviate(8).name();
    }

    private static boolean isEmptyDir(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 清理失败不影响主流程：留下的是个谁都不会读到的孤儿目录
                        }
                    });
        } catch (IOException e) {
            log.warn("清理克隆失败的目录失败：{} - {}", dir, e.getMessage());
        }
    }

    /** 根因消息，截断后给用户看。 */
    private static String reasonOf(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() <= MAX_REASON ? message : message.substring(0, MAX_REASON) + "…";
    }

    /** 一次同步的结果。 */
    public record SyncOutcome(boolean success, String message, int changedCount, String commitId) {
    }

    /** 工作副本状态快照。{@code present=false} 表示这个目录当前不是可用的 Git 工作副本。 */
    public record RepoStatus(boolean present, boolean clean, int changedCount, int ahead, int behind,
                             String headCommit, List<String> changedPaths) {

        public static RepoStatus absent() {
            return new RepoStatus(false, true, 0, 0, 0, null, List.of());
        }
    }
}
