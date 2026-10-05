package cn.minims.minidocs.user.service.impl;

import cn.minims.minidocs.common.util.JsonUtil;
import cn.minims.minidocs.common.util.TimeUtil;
import cn.minims.minidocs.user.entity.UserSettings;
import cn.minims.minidocs.user.mapper.UserSettingsMapper;
import cn.minims.minidocs.user.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements SettingsService {

    /** 默认外观偏好（与前端 src/stores/settings.ts 保持一致）。 */
    public static final Map<String, Object> DEFAULTS = Map.of(
            "showDocTree", true,
            "contentWidth", 900,
            "codeTheme", "github",
            "themeMode", "system",
            "defaultView", "read",
            "kbView", "grid");

    private final UserSettingsMapper userSettingsMapper;

    @Override
    public Map<String, Object> getSettings(Long userId) {
        UserSettings entity = userSettingsMapper.selectById(userId);
        if (entity == null) {
            Map<String, Object> defaults = new LinkedHashMap<>(DEFAULTS);
            save(userId, JsonUtil.toJson(defaults));
            return defaults;
        }
        Map<String, Object> stored = JsonUtil.toMap(entity.getSettings());
        Map<String, Object> merged = new LinkedHashMap<>(DEFAULTS);
        merged.putAll(stored);
        return merged;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> mergeSettings(Long userId, Map<String, Object> patch) {
        Map<String, Object> current = getSettings(userId);
        if (patch != null) {
            patch.forEach((key, value) -> {
                if (value != null) {
                    current.put(key, value);
                }
            });
        }
        save(userId, JsonUtil.toJson(current));
        return current;
    }

    private void save(Long userId, String json) {
        UserSettings entity = userSettingsMapper.selectById(userId);
        if (entity == null) {
            entity = new UserSettings();
            entity.setUserId(userId);
            entity.setSettings(json);
            entity.setUpdatedAt(TimeUtil.now());
            userSettingsMapper.insert(entity);
        } else {
            entity.setSettings(json);
            entity.setUpdatedAt(TimeUtil.now());
            userSettingsMapper.updateById(entity);
        }
    }
}
