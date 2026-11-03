package cn.minims.minidocs.git;

import cn.minims.minidocs.common.exception.BizException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Git 工作副本的数据保护：拉取失败时，临时收起的本地改动必须原样放回。
 */
@DisplayName("云端知识库 Git 工作副本")
class GitVaultServiceTest {

    @TempDir
    Path tmp;

    private final GitVaultService service = new GitVaultService();

    private Path work;

    @BeforeEach
    void setUp() throws Exception {
        // 远端就用一个普通仓库：pull 直接从这里 fetch，不必先造裸仓库再推
        Path remote = tmp.resolve("remote-src");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("readme.md"), "hello");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("tester", "tester@example.com").call();
        }
        work = tmp.resolve("work");
        try (Git ignored = Git.cloneRepository().setURI(remote.toUri().toString())
                .setBranch("main").setDirectory(work.toFile()).call()) {
            // 工作副本
        }
    }

    @Test
    @DisplayName("拉取失败（网络错误是受检异常）也要恢复 stash，未跟踪文件不丢")
    void failedPullRestoresStashedFiles() throws Exception {
        Path note = work.resolve("我的笔记.md");
        Files.writeString(note, "local draft");

        // 把远端指向不存在的本地仓库：fetch 会抛受检的 TransportException，且很快返回
        try (Git git = Git.open(work.toFile())) {
            git.remoteSetUrl().setRemoteName("origin")
                    .setRemoteUri(new URIish(tmp.resolve("missing.git").toUri().toString())).call();
        }

        assertThatThrownBy(() -> service.pull(work, "main", null, null, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("拉取失败");

        assertThat(note).exists();
        assertThat(Files.readString(note)).isEqualTo("local draft");
        try (Git git = Git.open(work.toFile())) {
            assertThat(git.stashList().call()).as("失败后不能把改动留在 stash 里").isEmpty();
        }
    }
}
