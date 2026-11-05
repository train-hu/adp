package com.ops.deploy.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 种子用户逻辑：app_user 表为空时插入多角色演示账号（admin/ops，均 BCrypt）。
 *
 * <p>账号集合见 {@link #SEED_USERS}：admin(ADMIN)、ops(OPS)，
 * 用于演示 RBAC 分层与审计。两个账号共用 {@code deploy.seed-password} 密码，
 * 生产环境必须通过 DEPLOY_SEED_PASSWORD 环境变量覆盖默认密码。
 */
@Service
public class SeedUserService {

    public static final String SEED_USERNAME = "admin";

    /** 种子账号：username → role（顺序即落库顺序）。 */
    static final Map<String, Role> SEED_USERS = Map.of(
            "admin", Role.ADMIN,
            "ops", Role.OPS);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    @Autowired
    public SeedUserService(UserRepository userRepository,
                           @Value("${deploy.seed-password:admin123}") String seedPassword) {
        this(userRepository, seedPassword, new BCryptPasswordEncoder());
    }

    /** 可注入自定义 PasswordEncoder（便于单测校验 BCrypt）。 */
    public SeedUserService(UserRepository userRepository,
                           String seedPassword,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.seedPassword = seedPassword;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void ensureSeedUser() {
        if (userRepository.count() > 0) {
            return;
        }
        for (Map.Entry<String, Role> entry : SEED_USERS.entrySet()) {
            userRepository.save(new UserEntity(
                    entry.getKey(),
                    passwordEncoder.encode(seedPassword),
                    entry.getValue().name()));
        }
    }
}
