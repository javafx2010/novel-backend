package com.novel.browse.repository;

import com.novel.browse.domain.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByToken(String token);

    int deleteByToken(String token);

    /**
     * Drops sessions that ran out, called on login so the table does not grow forever
     */
    int deleteByExpiresAtBefore(LocalDateTime now);
}
