package cn.minims.minidocs.kb.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库维护名单。只在 {@code maintain_scope='members'} 时参与写权限判定。
 *
 * <p>role 只有 EDITOR / VIEWER，且名单<b>只追加授权、不削减</b>组织管理员与创建者的权限（规范 §2.3）。</p>
 *
 * <p>名单只在组织内生效（§9）：被授权人必须是该库所属组织的活跃成员，否则本表一行不产生任何权利。
 * 这样「把人移出组织」就是完整的撤销动作，不会留下能继续读写的名单行。</p>
 */
@Data
@TableName("kb_member")
public class KbMember {

    public static final String ROLE_EDITOR = "EDITOR";
    public static final String ROLE_VIEWER = "VIEWER";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private Long userId;

    private String role;

    private Long grantedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public boolean isEditor() {
        return ROLE_EDITOR.equalsIgnoreCase(role);
    }
}
