package com.projtimeline.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.projtimeline.user.UserEntity;
import com.projtimeline.user.UserMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {
    private final AppProperties properties;
    private final Environment environment;
    private final UserMapper users;
    private final PasswordEncoder encoder;
    private final ConfigurableApplicationContext context;

    public AdminBootstrapRunner(AppProperties properties, Environment environment, UserMapper users, PasswordEncoder encoder,
                                ConfigurableApplicationContext context) {
        this.properties = properties;
        this.environment = environment;
        this.users = users;
        this.encoder = encoder;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.bootstrapAdmin()) return;
        if (users.selectCount(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getGlobalRole, "ADMIN")) > 0) {
            throw new IllegalStateException("Administrator bootstrap refused: an ADMIN already exists");
        }
        String username = required("BOOTSTRAP_ADMIN_USERNAME");
        String password = required("BOOTSTRAP_ADMIN_PASSWORD");
        String name = required("BOOTSTRAP_ADMIN_NAME");
        if (password.length() < 8 || password.length() > 72) throw new IllegalStateException("Bootstrap password length must be 8..72");
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setName(name);
        user.setEmail(environment.getProperty("BOOTSTRAP_ADMIN_EMAIL"));
        user.setGlobalRole("ADMIN");
        user.setActive(true);
        users.insert(user);
        System.out.println("Administrator bootstrap completed for username: " + username);
        context.close();
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for administrator bootstrap");
        return value.trim();
    }
}
