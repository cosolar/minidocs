package cn.minims.minidocs.doc.service;

import cn.minims.minidocs.common.context.LoginUser;
import cn.minims.minidocs.doc.dto.DocDtos.LockVO;

/**
 * 分工式编辑锁（规范 §4.3，F8）。
 *
 * <p>锁不试图解决「同时编辑」，只是把它变成看得见的状态：拿到锁才允许保存，
 * 拿不到就降级只读并显示持有者。配合 {@code saveDoc} 的基线比对（412）覆盖锁失效的窗口。</p>
 */
public interface DocLockService {

    /**
     * 获取或续期，幂等。
     *
     * <p>本人持有 → 延长到期时间；无人持有或锁已过期 → 接管；他人有效持有 → 409 且响应体带持有者信息。</p>
     */
    LockVO acquire(Long kbId, String path, LoginUser actor);

    /**
     * 释放。持有者本人可释放，具 {@code KB_MEMBER_MANAGE} 权者可强制解锁（写审计）。
     *
     * <p>没有锁时静默成功：关两次标签页、{@code sendBeacon} 与手动释放撞车，都不该报错。</p>
     */
    void release(Long kbId, String path, LoginUser actor);

    /** 当前锁状态（需可读）：编辑器每 10s 轮询它，锁空出来就恢复可写。 */
    LockVO probe(Long kbId, String path, LoginUser actor);

    /**
     * 保存前置校验：本人未持有有效锁 → 403（提示「先获取编辑锁」），
     * 他人正在持有 → 409（编辑器直接显示持有者）。
     */
    void requireHeldBy(Long kbId, String normalizedPath, LoginUser actor);
}
