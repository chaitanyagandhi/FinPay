package com.finpay.user.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers this service's own configuration properties. */
@Configuration
@EnableConfigurationProperties(UserSearchProperties.class)
public class UserServiceConfig {}
