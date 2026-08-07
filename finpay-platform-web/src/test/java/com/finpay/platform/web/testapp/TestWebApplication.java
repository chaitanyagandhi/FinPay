package com.finpay.platform.web.testapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal servlet application used to exercise the shared web behaviour end to end.
 *
 * <p>The module ships no application of its own, so the auto-configuration is verified against a
 * real one: a service that adds this dependency should get request ids and the error envelope with
 * no further wiring, and this is what proves that.
 */
@SpringBootApplication
public class TestWebApplication {}
