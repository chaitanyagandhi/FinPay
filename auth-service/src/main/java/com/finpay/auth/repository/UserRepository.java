package com.finpay.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.finpay.auth.entity.User;

/** Data access for {@link User}. Emails are always queried in their normalised, lower-cased form. */
public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);
}
