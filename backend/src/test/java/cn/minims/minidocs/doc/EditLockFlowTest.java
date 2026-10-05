package cn.minims.minidocs.doc;

import cn.minims.minidocs.audit.dto.AuditDtos.AuditVO;
import cn.minims.minidocs.audit.entity.AuditLog;
import cn.minims.minidocs.audit.mapper.AuditLogMapper;
import cn.minims.minidocs.audit.service.AuditService;
import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.common.util.StorageKey;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.doc.dto.DocDtos.DocContentVO;
import cn.minims.minidocs.doc.dto.DocDtos.LockVO;
import cn.minims.minidocs.doc.entity.DocEditLock;
import cn.minims.minidocs.doc.mapper.DocEditLockMapper;
import cn.minims.minidocs.doc.service.DocLockService;
import cn.minims.minidocs.doc.service.DocService;
import cn.minims.minidocs.doc.service.VaultFileService;
import cn.minims.minidocs.kb.entity.KbMember;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.mapper.KbMemberMapper;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.support.MiniDocsTestBase;
import cn.minims.minidocs.tenant.entity.Tenant;
import cn.minims.minidocs.tenant.entity.TenantMember;
import cn.minims.minidocs.tenant.mapper.TenantMapper;
import cn.minims.minidocs.tenant.mapper.TenantMemberMapper;
import cn.minims.minidocs.user.entity.User;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 分工式编辑锁与保存基线（规范 §4.3 / F8）。
 *
 * <p>锁与基线是一对：锁挡住「同时在写」，基线挡住「锁没挡住的那部分」—— 锁过期后仍在编辑的标签页、
 * 直接改磁盘的人。所以两半都要有各自的拒绝路径，缺一不可。</p>
 */
@DisplayName("M4 编辑锁与保存基线")
class EditLockFlowTest extends MiniDocsTestBase {

    private static final String DOC = "指南/第一章.md";

    /** slug 每个用例独占：DB 会回滚，磁盘不会 —— 共用一个目录会让后面的用例撞上前面留下的文件。 */
    private static final AtomicInteger SLUG = new AtomicInteger();

    @Autowired
    private DocService docService;

    @Autowired
    private DocLockService lockService;

    @Autowired
    private DocEditLockMapper lockMapper;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private KbMemberMapper kbMemberMapper;

    @Autowired
    private VaultFileService vaultFileService;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private TenantMemberMapper tenantMemberMapper;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogMapper auditLogMapper;

    /** 容器里那份真正的序列化配置：基线要经 JSON 往返，用的就不该是测试自建的 mapper。 */
    @Autowired
    private ObjectMapper objectMapper;

    private Tenant team;
    private Long kbId;
    private LoginUser alice;
    private LoginUser bob;
    private LoginUser kate;

    @BeforeEach
    void seed() {
        alice = as(saveUser("lock-alice", "爱丽丝", User.ROLE_USER));
        bob = as(saveUser("lock-bob", "鲍勃", User.ROLE_USER));
        kate = as(saveUser("lock-kate", "凯特", User.ROLE_USER));
        team = teamOrg("lock-acme");
        member(team.getId(), alice, TenantMember.ROLE_OWNER);
        member(team.getId(), bob, TenantMember.ROLE_MEMBER);
        member(team.getId(), kate, TenantMember.ROLE_ADMIN);
        kbId = newKb();
        roster(bob, KbMember.ROLE_EDITOR);
        docService.createDoc(kbId, DOC, "# 第一章\n\n初稿", alice);
    }

    // ------------------------------------------------------------------ 锁本身

    @Nested
    @DisplayName("锁的获取、续期与过期")
    class LockLifecycle {

        @Test
        @DisplayName("无人持有时 probe 报「空闲」并照旧下发 TTL")
        void probeFree() {
            LockVO free = lockService.probe(kbId, DOC, alice);
            assertThat(free.locked()).isFalse();
            assertThat(free.holderUserId()).isNull();
            assertThat(free.ttlSeconds()).isPositive();
        }

        @Test
        @DisplayName("获取后本人持有，nickname 用展示名而不是用户名")
        void acquireReportsHolder() {
            LockVO lock = lockService.acquire(kbId, DOC, alice);
            assertThat(lock.locked()).isTrue();
            assertThat(lock.mine()).isTrue();
            assertThat(lock.holder()).isEqualTo("lock-alice");
            assertThat(lock.nickname()).isEqualTo("爱丽丝");
            assertThat(lock.expiresAt()).isAfter(TimeUtil.now());
        }

        @Test
        @DisplayName("他人有效持有时获取 → 409，且响应体带回持有者")
        void secondAcquireConflicts() {
            lockService.acquire(kbId, DOC, alice);
            BizException conflict = catchBiz(() -> lockService.acquire(kbId, DOC, bob));
            assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.DOC_LOCKED);
            assertThat(conflict.getMessage()).contains("爱丽丝");
            assertThat(conflict.getPayload()).isInstanceOf(LockVO.class);
            assertThat(((LockVO) conflict.getPayload()).holderUserId()).isEqualTo(alice.id());
        }

        @Test
        @DisplayName("重复获取只推到期时间，acquired_at 不变（UI 要显示「已持有 N 秒」）")
        void renewKeepsAcquiredAt() {
            LockVO first = lockService.acquire(kbId, DOC, alice);
            ageLock(first.acquiredAt().minusSeconds(1));
            LockVO renewed = lockService.acquire(kbId, DOC, alice);
            assertThat(renewed.acquiredAt()).isEqualTo(first.acquiredAt());
            assertThat(renewed.expiresAt()).isAfterOrEqualTo(first.expiresAt());
        }

        @Test
        @DisplayName("过期锁由下一个获取者接管，不算冲突")
        void expiredLockIsTakenOver() {
            lockService.acquire(kbId, DOC, alice);
            ageLock(TimeUtil.now().minusSeconds(5));
            LockVO taken = lockService.acquire(kbId, DOC, bob);
            assertThat(taken.mine()).isTrue();
            assertThat(taken.holderUserId()).isEqualTo(bob.id());
        }

        @Test
        @DisplayName("过期锁在 probe 眼里等同空闲：客户端据此直接重试获取")
        void expiredLooksFree() {
            lockService.acquire(kbId, DOC, alice);
            ageLock(TimeUtil.now().minusSeconds(5));
            assertThat(lockService.probe(kbId, DOC, bob).locked()).isFalse();
        }

        @Test
        @DisplayName("释放后回到空闲；重复释放静默成功（sendBeacon 会撞车）")
        void releaseIsIdempotent() {
            lockService.acquire(kbId, DOC, alice);
            lockService.release(kbId, DOC, alice);
            assertThat(lockService.probe(kbId, DOC, alice).locked()).isFalse();
            lockService.release(kbId, DOC, alice);
            assertThat(lockService.acquire(kbId, DOC, bob).mine()).isTrue();
        }

        @Test
        @DisplayName("只读成员拿不到锁：DOC_WRITE 门在锁这里同样生效")
        void readOnlyMemberCannotLock() {
            roster(bob, KbMember.ROLE_VIEWER);
            assertBizError(ErrorCode.FORBIDDEN, () -> lockService.acquire(kbId, DOC, bob));
        }

        @Test
        @DisplayName("组织外的人拿锁 → 404，不确认文档存在")
        void outsiderGetsNotFound() {
            LoginUser stranger = as(saveUser("lock-stranger", "路人", User.ROLE_USER));
            assertBizError(ErrorCode.NOT_FOUND, () -> lockService.acquire(kbId, DOC, stranger));
        }
    }

    // ------------------------------------------------------------------ 保存前置

    @Nested
    @DisplayName("saveDoc 的两道前置：锁 + 磁盘基线")
    class SaveGates {

        @Test
        @DisplayName("无锁保存 → 403，提示先获取锁")
        void saveRequiresLock() {
            DocContentVO doc = docService.read(kbId, DOC, alice);
            BizException e = catchBiz(() -> docService.saveDoc(kbId, DOC, "直接写",
                    doc.size(), doc.modifiedAt(), alice));
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(e.getMessage()).contains("编辑锁");
            assertThat(content()).contains("初稿");
        }

        @Test
        @DisplayName("持锁 + 基线相符 → 保存成功")
        void happyPathWrites() {
            lockService.acquire(kbId, DOC, alice);
            DocContentVO doc = docService.read(kbId, DOC, alice);
            docService.saveDoc(kbId, DOC, "# 第一章\n\n定稿", doc.size(), doc.modifiedAt(), alice);
            assertThat(content()).contains("定稿");
        }

        @Test
        @DisplayName("锁被别人抢走后保存 → 409，不是 403：编辑器要显示的是「谁在写」")
        void saveConflictsWithHolder() {
            lockService.acquire(kbId, DOC, alice);
            DocContentVO doc = docService.read(kbId, DOC, alice);
            lockService.release(kbId, DOC, alice);
            lockService.acquire(kbId, DOC, bob);
            BizException e = catchBiz(() -> docService.saveDoc(kbId, DOC, "我的版本",
                    doc.size(), doc.modifiedAt(), alice));
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOC_LOCKED);
            assertThat(((LockVO) e.getPayload()).holder()).isEqualTo("lock-bob");
        }

        @Test
        @DisplayName("缺基线 → 412，并带回磁盘当前基线供编辑器续手")
        void missingBaselineIsPreconditionFailed() {
            lockService.acquire(kbId, DOC, alice);
            BizException e = catchBiz(() -> docService.saveDoc(kbId, DOC, "我的版本", null, null, alice));
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STALE_WRITE);
            assertThat(payloadSize(e)).isEqualTo(vaultFileService.sizeOf(file()));
        }

        @Test
        @DisplayName("磁盘 size 变了 → 412，写进去的还是别人的内容")
        void staleSizeIsRejected() {
            lockService.acquire(kbId, DOC, alice);
            DocContentVO doc = docService.read(kbId, DOC, alice);
            outsideEdit("# 第一章\n\n有人在磁盘上改了很长的内容");
            BizException e = catchBiz(() -> docService.saveDoc(kbId, DOC, "我的版本",
                    doc.size(), doc.modifiedAt(), alice));
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STALE_WRITE);
            assertThat(content()).contains("磁盘上改了");
            assertThat(payloadSize(e)).isEqualTo(vaultFileService.sizeOf(file()));
        }

        @Test
        @DisplayName("只有时间戳变了（size 恰好相同）也要拒：基线比对是两列，不是一列")
        void staleMtimeAloneIsRejected() throws IOException {
            lockService.acquire(kbId, DOC, alice);
            DocContentVO doc = docService.read(kbId, DOC, alice);
            Files.setLastModifiedTime(file(), FileTime.from(
                    doc.modifiedAt().minusSeconds(30).atZone(TimeUtil.ZONE).toInstant()));
            assertBizError(ErrorCode.STALE_WRITE, () -> docService.saveDoc(kbId, DOC, "我的版本",
                    doc.size(), doc.modifiedAt(), alice));
        }

        @Test
        @DisplayName("文档被他人删除 → 412 带 missing，而不是 500")
        void deletedUnderneathReportsMissing() {
            lockService.acquire(kbId, DOC, alice);
            vaultFileService.delete(vaultFileService.rootOf(storageKey()), DOC);
            BizException e = catchBiz(() -> docService.saveDoc(kbId, DOC, "我的版本",
                    10L, TimeUtil.now(), alice));
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STALE_WRITE);
            assertThat(String.valueOf(e.getPayload())).contains("missing");
        }
    }

    // ------------------------------------------------------------------ JSON 往返

    @Nested
    @DisplayName("基线经 JSON 往返仍可比")
    class BaselineRoundTrip {

        @Test
        @DisplayName("modifiedAt 序列化成 ISO 字符串，回读后按秒仍等于原值")
        void localDateTimeSurvivesJson() throws IOException {
            DocContentVO doc = docService.read(kbId, DOC, alice);
            String json = objectMapper.writeValueAsString(doc);
            JsonNode node = objectMapper.readTree(json).get("modifiedAt");
            assertThat(node.isTextual()).isTrue();
            assertThat(node.asText()).contains("T");
            LocalDateTime back = objectMapper.readValue(json, DocContentVO.class).modifiedAt();
            assertThat(back.truncatedTo(ChronoUnit.SECONDS))
                    .isEqualTo(doc.modifiedAt().truncatedTo(ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("秒为 0 的时刻（ISO 会省掉秒位）也不会被误判为冲突")
        void zeroSecondTimestampStillMatches() throws IOException {
            DocContentVO doc = docService.read(kbId, DOC, alice);
            LocalDateTime onTheMinute = doc.modifiedAt().withNano(0).withSecond(0);
            Files.setLastModifiedTime(file(),
                    FileTime.from(onTheMinute.atZone(TimeUtil.ZONE).toInstant()));
            lockService.acquire(kbId, DOC, alice);
            DocContentVO fresh = docService.read(kbId, DOC, alice);
            String body = objectMapper.writeValueAsString(fresh);
            DocContentVO reread = objectMapper.readValue(body, DocContentVO.class);
            docService.saveDoc(kbId, DOC, "# 第一章\n\n整分保存", reread.size(), reread.modifiedAt(), alice);
            assertThat(content()).contains("整分保存");
        }
    }

    // ------------------------------------------------------------------ 强解与审计

    @Nested
    @DisplayName("强制解锁与审计留痕（F9）")
    class ForceUnlockAndAudit {

        @Test
        @DisplayName("普通成员不能替别人解锁")
        void memberCannotReleaseOthers() {
            lockService.acquire(kbId, DOC, alice);
            BizException e = catchBiz(() -> lockService.release(kbId, DOC, bob));
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(lockService.probe(kbId, DOC, alice).holderUserId()).isEqualTo(alice.id());
        }

        @Test
        @DisplayName("管理员解锁：锁没了，且留下一条带 holder 的审计")
        void adminCanForceUnlockAndItIsLogged() {
            lockService.acquire(kbId, DOC, alice);
            lockService.release(kbId, DOC, kate);
            assertThat(lockService.probe(kbId, DOC, kate).locked()).isFalse();
            AuditVO entry = onlyAuditRow();
            assertThat(entry.action()).isEqualTo("KB_MEMBER_MANAGE");
            assertThat(entry.actor()).isEqualTo("凯特");
            assertThat(String.valueOf(entry.detail())).contains("forceUnlock").contains(String.valueOf(alice.id()));
        }

        @Test
        @DisplayName("解锁后原持有者保存 → 403，而不是把内容写进别人的编辑窗口")
        void saveAfterForceUnlockIsRefused() {
            lockService.acquire(kbId, DOC, alice);
            DocContentVO doc = docService.read(kbId, DOC, alice);
            lockService.release(kbId, DOC, kate);
            assertBizError(ErrorCode.FORBIDDEN, () -> docService.saveDoc(kbId, DOC, "我的版本",
                    doc.size(), doc.modifiedAt(), alice));
        }

        @Test
        @DisplayName("审计按组织隔离：本组织查得到，别的组织与缺失上下文都查不到")
        void auditIsScopedToTenant() {
            lockService.acquire(kbId, DOC, alice);
            lockService.release(kbId, DOC, kate);
            PageResult<AuditVO> mine = auditPage(team.getId(), null);
            assertThat(mine.list()).isNotEmpty();
            assertThat(mine.list()).allSatisfy(entry -> assertThat(entry.action()).isNotBlank());
            assertThat(auditPage(otherOrg().getId(), null).list()).isEmpty();
            assertThat(auditPage(null, null).list()).isEmpty();
        }

        @Test
        @DisplayName("动作过滤只出该动作，避免审计页把无关条目一起翻页")
        void auditFilterByAction() {
            lockService.acquire(kbId, DOC, alice);
            lockService.release(kbId, DOC, kate);
            assertThat(auditPage(team.getId(), "DOC_READ").list()).isEmpty();
            assertThat(auditPage(team.getId(), "KB_MEMBER_MANAGE").total()).isPositive();
        }

        @Test
        @DisplayName("按操作者与日期区间筛：筛的是整段记录，而不是当前这页二十条里挑")
        void auditFilterByActorAndDateRange() {
            insertAuditAgo(200);
            insertAuditAgo(3);
            lockService.acquire(kbId, DOC, alice);
            lockService.release(kbId, DOC, kate);

            assertThat(auditService.page(team.getId(), null, kate.id(), null, null, 1, 20).list())
                    .isNotEmpty()
                    .allSatisfy(entry -> assertThat(entry.actorUserId()).isEqualTo(kate.id()));

            LocalDateTime from = TimeUtil.now().minusDays(30);
            PageResult<AuditVO> recent = auditService.page(team.getId(), null, alice.id(), from, null, 1, 20);
            assertThat(recent.list()).isNotEmpty();
            assertThat(recent.list()).allSatisfy(entry -> assertThat(entry.createdAt()).isAfterOrEqualTo(from));
            // 200 天前那两条确实被区间挡在外面：不加日期时的总数严格更多
            assertThat(auditPage(team.getId(), null).total()).isGreaterThan(recent.total());

            LocalDateTime to = TimeUtil.now().minusDays(100);
            assertThat(auditService.page(team.getId(), null, alice.id(), null, to, 1, 20).list())
                    .allSatisfy(entry -> assertThat(entry.createdAt()).isBefore(to));
        }

        @Test
        @DisplayName("保留期外的条目被清掉，期内的留下")
        void retentionPurgesOldRows() {
            insertAuditAgo(200);
            insertAuditAgo(3);
            int removed = auditService.purgeOlderThan(TimeUtil.now().minusDays(180));
            assertThat(removed).isOne();
            assertThat(auditLogMapper.selectCount(Wrappers.<AuditLog>lambdaQuery()
                    .eq(AuditLog::getTenantId, team.getId()))).isOne();
        }

        @Test
        @DisplayName("删库后的审计仍可读：条目自带库名，不依赖已消失的 kb 行")
        void kbDeleteAuditKeepsName() {
            kbService.deleteKnowledgeBase(kbId, alice);
            AuditVO entry = onlyAuditRow();
            assertThat(entry.action()).isEqualTo("KB_DELETE");
            assertThat(entry.kbName()).isNull();
            assertThat(String.valueOf(entry.detail())).contains("库名");
        }
    }

    // ------------------------------------------------------------------ 夹具

    private String storageKey() {
        return kbService.getById(kbId).getStorageKey();
    }

    private Path file() {
        return vaultFileService.resolve(vaultFileService.rootOf(storageKey()), DOC);
    }

    private String content() {
        return vaultFileService.read(vaultFileService.rootOf(storageKey()), DOC);
    }

    /** 模拟「不经锁的第三方写入」：直接落盘，绕开 saveDoc 的全部前置。 */
    private void outsideEdit(String body) {
        vaultFileService.write(vaultFileService.rootOf(storageKey()), DOC, body);
    }

    private void ageLock(LocalDateTime expiresAt) {
        lockMapper.update(null, Wrappers.<DocEditLock>lambdaUpdate()
                .eq(DocEditLock::getKbId, kbId)
                .eq(DocEditLock::getDocPath, DOC)
                .set(DocEditLock::getExpiresAt, expiresAt));
    }

    @SuppressWarnings("unchecked")
    private long payloadSize(BizException e) {
        return ((Number) ((Map<String, Object>) e.getPayload()).get("baseSize")).longValue();
    }

    private AuditVO onlyAuditRow() {
        PageResult<AuditVO> page = auditPage(team.getId(), null);
        assertThat(page.list()).hasSize(1);
        return page.list().get(0);
    }

    /** 不带操作者与日期区间的读法：断言「有什么」的用语大多只关心动作与组织隔离。 */
    private PageResult<AuditVO> auditPage(Long tenantId, String action) {
        return auditService.page(tenantId, action, null, null, null, 1, 20);
    }

    private void insertAuditAgo(int days) {
        AuditLog entry = new AuditLog();
        entry.setTenantId(team.getId());
        entry.setActorUserId(alice.id());
        entry.setAction("KB_MEMBER_MANAGE");
        entry.setCreatedAt(TimeUtil.now().minusDays(days));
        auditLogMapper.insert(entry);
    }

    private Tenant otherOrg() {
        Tenant other = teamOrg("lock-other");
        member(other.getId(), kate, TenantMember.ROLE_OWNER);
        return other;
    }

    private Tenant teamOrg(String slug) {
        Tenant tenant = new Tenant();
        tenant.setSlug(slug);
        tenant.setName(slug);
        tenant.setType(Tenant.TYPE_TEAM);
        tenant.setOwnerUserId(alice.id());
        tenant.setJoinPolicy(Tenant.JOIN_REQUEST);
        tenant.setDiscoverable(true);
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        tenantMapper.insert(tenant);
        return tenant;
    }

    private void member(Long tenantId, LoginUser user, String role) {
        TenantMember row = new TenantMember();
        row.setTenantId(tenantId);
        row.setUserId(user.id());
        row.setRole(role);
        row.setJoinedFrom("test");
        tenantMemberMapper.insert(row);
    }

    private void roster(LoginUser user, String role) {
        kbMemberMapper.delete(Wrappers.<KbMember>lambdaQuery()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, user.id()));
        KbMember row = new KbMember();
        row.setKbId(kbId);
        row.setUserId(user.id());
        row.setRole(role);
        row.setGrantedBy(alice.id());
        kbMemberMapper.insert(row);
    }

    /** members 档位：锁与保存要判的是「两名维护者互相争锁」，owner_only 下第二个人根本写不了。 */
    private Long newKb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(team.getId());
        kb.setOwnerId(alice.id());
        kb.setName("库名");
        kb.setSlug("lock-kb-" + SLUG.incrementAndGet());
        kb.setStorageKey(StorageKey.of(team.getId(), kb.getSlug()));
        kb.setVisibility(KnowledgeBase.VISIBILITY_ORG);
        kb.setMaintainScope(KnowledgeBase.MAINTAIN_MEMBERS);
        kb.setDocCount(0);
        kbService.save(kb);
        vaultFileService.ensureRoot(kb.getStorageKey());
        return kb.getId();
    }
}
