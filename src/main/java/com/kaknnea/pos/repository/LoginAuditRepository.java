package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.LoginAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAuditRepository extends JpaRepository<LoginAudit, Long> {
}
