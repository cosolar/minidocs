package cn.minims.minidocs.user.service.impl;

import cn.minims.minidocs.user.entity.User;
import cn.minims.minidocs.user.mapper.UserMapper;
import cn.minims.minidocs.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Override
    public User findByUsernameOrEmail(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String value = keyword.trim().toLowerCase(Locale.ROOT);
        return getOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, value)
                .or()
                .eq(User::getEmail, value)
                .last("LIMIT 1"), false);
    }

    @Override
    public User findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return getOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username.trim().toLowerCase(Locale.ROOT))
                .last("LIMIT 1"), false);
    }

    @Override
    public boolean usernameExists(String username, Long excludeId) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return count(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username.trim().toLowerCase(Locale.ROOT))
                .ne(excludeId != null, User::getId, excludeId)) > 0;
    }

    @Override
    public boolean emailExists(String email, Long excludeId) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return count(Wrappers.<User>lambdaQuery()
                .eq(User::getEmail, email.trim().toLowerCase(Locale.ROOT))
                .ne(excludeId != null, User::getId, excludeId)) > 0;
    }
}
