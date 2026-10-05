package cn.minims.minidocs.user.service;

import cn.minims.minidocs.common.api.ErrorCode;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.exception.BizException;
import cn.minims.minidocs.kb.entity.KnowledgeBase;
import cn.minims.minidocs.kb.service.KnowledgeBaseService;
import cn.minims.minidocs.share.entity.Share;
import cn.minims.minidocs.share.mapper.ShareMapper;
import cn.minims.minidocs.tenant.service.TenantMemberService;
import cn.minims.minidocs.user.dto.UserDtos.AdminUpdateRequest;
import cn.minims.minidocs.user.dto.UserDtos.AdminUserVO;
import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.mapper.UserSettingsMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 用户管理（仅管理员）：列表 / 审核 / 禁用 / 删除 / 重置密码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final List<String> VALID_STATUS = List.of(
            User.STATUS_ACTIVE, User.STATUS_PENDING, User.STATUS_REJECTED, User.STATUS_DISABLED);

    private final UserService userService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final TenantMemberService tenantMemberService;
    private final ShareMapper shareMapper;
    private final UserSettingsMapper userSettingsMapper;
    private final PasswordEncoder passwordEncoder;

    public PageResult<AdminUserVO> page(String keyword, String status, long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), 100);
        LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase(Locale.ROOT);
            wrapper.and(w -> w.like(User::getUsername, kw)
                    .or().like(User::getEmail, kw)
                    .or().like(User::getDisplayName, keyword.trim()));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(User::getStatus, status.trim());
        }
        wrapper.orderByAsc(User::getId);
        Page<User> result = userService.page(new Page<>(current, pageSize), wrapper);

        List<AdminUserVO> list = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO update(Long id, AdminUpdateRequest request) {
        User user = requireUser(id);
        if (request.displayName() != null) {
            user.setDisplayName(trimToNull(request.displayName()));
        }
        if (request.email() != null) {
            String email = trimToNull(request.email());
            if (email != null) {
                email = email.toLowerCase(Locale.ROOT);
                if (userService.emailExists(email, id)) {
                    throw BizException.exists("邮箱已被占用");
                }
            }
            user.setEmail(email);
        }
        if (request.role() != null && !request.role().isBlank()) {
            if (user.isAdmin() && !User.ROLE_ADMIN.equalsIgnoreCase(request.role())) {
                throw BizException.forbidden("内置管理员角色不可变更");
            }
            user.setRole(request.role().trim().toUpperCase(Locale.ROOT));
        }
        userService.updateById(user);
        return toVO(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO changeStatus(Long id, String status) {
        User user = requireUser(id);
        if (status == null || !VALID_STATUS.contains(status)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "非法的账号状态");
        }
        if (user.isAdmin() && !User.STATUS_ACTIVE.equals(status)) {
            throw BizException.forbidden("内置管理员不可被禁用或拒绝");
        }
        User update = new User();
        update.setId(id);
        update.setStatus(status);
        userService.updateById(update);
        user.setStatus(status);
        return toVO(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String newPassword) {
        requireUser(id);
        User update = new User();
        update.setId(id);
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        userService.updateById(update);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        User user = requireUser(id);
        if (user.isAdmin()) {
            throw BizException.forbidden("内置管理员不可删除");
        }
        // 这里按用户清理而不是逐个走删除接口：删库的治理权判定对人，注销流程没有「人」可用。
        // 先清库再清成员身份：个人组织随账号消失，而「名下已无库」是那条判定的前提；
        // 「他仍是团队组织 OWNER」这条拒绝会回滚整笔事务，所以顺序不影响到不到位。
        int purged = knowledgeBaseService.purgeOwnedBy(id);
        tenantMemberService.purgeUserMemberships(id);
        knowledgeBaseService.purgeFavoritesOf(id);
        shareMapper.delete(Wrappers.<Share>lambdaQuery().eq(Share::getOwnerId, id));
        userSettingsMapper.deleteById(id);
        userService.removeById(id);
        log.info("管理员删除用户 id={} username={}，连带清理 {} 个知识库", id, user.getUsername(), purged);
    }

    private User requireUser(Long id) {
        User user = userService.getById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return user;
    }

    private AdminUserVO toVO(User user) {
        long kbCount = knowledgeBaseService.count(
                Wrappers.<KnowledgeBase>lambdaQuery().eq(KnowledgeBase::getOwnerId, user.getId()));
        return new AdminUserVO(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getRole(), user.getStatus(), kbCount, user.getCreatedAt());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
