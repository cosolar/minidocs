package cn.minims.minidocs.user.service;

import java.util.Map;

public interface SettingsService {

    /** 读取用户偏好（不存在时返回默认值并落库）。 */
    Map<String, Object> getSettings(Long userId);

    /** 浅合并保存。 */
    Map<String, Object> mergeSettings(Long userId, Map<String, Object> patch);
}
