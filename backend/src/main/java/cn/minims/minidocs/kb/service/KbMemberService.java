package cn.minims.minidocs.kb.service;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.kb.dto.KbDtos.RosterEntryVO;
import cn.minims.minidocs.kb.dto.KbDtos.RosterGrantRequest;

import java.util.List;

/**
 * 知识库维护名单（规范 §2.3 的 {@code members} 档）。
 *
 * <p>名单是组织内的加法：只追加授权，不削减组织管理员与创建者的权限，也不跨组织授权（§9）。
 * 「谁能写这个库」的判定全在 {@code AccessService}，本服务只负责这张表的增删改查与写入时的资格校验。</p>
 */
public interface KbMemberService {

    /** 名单 + 每人角色。过读门即可见：EDITOR 也该知道还有谁能写。 */
    List<RosterEntryVO> list(Long kbId, LoginUser actor);

    /**
     * 授权 / 改角色 / 撤销（{@code role} 传 null 即移除）。
     *
     * <p>被授权人必须是该库所属组织的活跃成员 —— 判定层本来就不认组织外的名单行，
     * 这里再挡一次是为了让 UI 得到一条错误，而不是一个「保存成功但什么也没发生」。</p>
     */
    RosterEntryVO grant(Long kbId, RosterGrantRequest request, LoginUser actor);

    /** 改 {@code maintain_scope}（KB_MEMBER_MANAGE）。 */
    String updateMaintainScope(Long kbId, String scope, LoginUser actor);
}
