package com.finpay.auth.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.finpay.auth.entity.LoginAttempt;

/** Append-only store of sign-in attempts. */
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

    long countByEmailAndSuccessfulFalse(String email);
}
