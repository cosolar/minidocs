package cn.minims.minidocs.user.service;

import cn.minims.minidocs.user.entity.User;
import com.baomidou.mybatisplus.spring.service.IService;

public interface UserService extends IService<User> {

    /** 按用户名或邮箱查找（忽略大小写）。 */
    User findByUsernameOrEmail(String keyword);

    User findByUsername(String username);

    boolean usernameExists(String username, Long excludeId);

    boolean emailExists(String email, Long excludeId);
}
