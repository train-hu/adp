package com.ops.deploy.user;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 应用启动时确保种子用户存在。
 */
@Component
public class DataInitializer {

    private final SeedUserService seedUserService;

    public DataInitializer(SeedUserService seedUserService) {
        this.seedUserService = seedUserService;
    }

    @PostConstruct
    public void init() {
        seedUserService.ensureSeedUser();
    }
}
