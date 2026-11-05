package com.ops.deploy.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeedUserServiceTest {

    @Mock
    private UserRepository userRepository;

    private SeedUserService seedUserService;

    @BeforeEach
    void setUp() {
        seedUserService = new SeedUserService(userRepository, "test-pass");
    }

    @Test
    void ensureSeedUser_emptyTable_shouldInsertAllSeedRoles() {
        when(userRepository.count()).thenReturn(0L);

        seedUserService.ensureSeedUser();

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository, times(SeedUserService.SEED_USERS.size())).save(captor.capture());

        List<UserEntity> saved = captor.getAllValues();
        Map<String, UserEntity> byUsername = saved.stream()
                .collect(Collectors.toMap(UserEntity::getUsername, Function.identity()));

        // 两个角色账号各一条（仅保留 ADMIN/OPS）
        assertThat(byUsername.keySet())
                .containsExactlyInAnyOrder("admin", "ops");
        assertThat(byUsername.get("admin").getRole()).isEqualTo(Role.ADMIN.name());
        assertThat(byUsername.get("ops").getRole()).isEqualTo(Role.OPS.name());

        // 全部启用、密码为 seedPassword 的 BCrypt
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        for (UserEntity u : saved) {
            assertThat(u.isEnabled()).isTrue();
            assertThat(encoder.matches("test-pass", u.getPasswordHash())).isTrue();
        }
    }

    @Test
    void ensureSeedUser_nonEmptyTable_shouldNotInsert() {
        when(userRepository.count()).thenReturn(1L);

        seedUserService.ensureSeedUser();

        verify(userRepository, never()).save(any(UserEntity.class));
    }
}
