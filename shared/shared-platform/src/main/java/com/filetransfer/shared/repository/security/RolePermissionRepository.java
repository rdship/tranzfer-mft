package com.filetransfer.shared.repository.security;

import com.filetransfer.shared.entity.security.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {

    @Query("SELECT rp FROM RolePermission rp JOIN FETCH rp.permission WHERE rp.role = :role")
    List<RolePermission> findByRoleWithPermission(@Param("role") String role);

    @Query("SELECT rp.permission.name FROM RolePermission rp WHERE rp.role = :role")
    List<String> findPermissionNamesByRole(@Param("role") String role);
}
