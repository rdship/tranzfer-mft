package com.filetransfer.shared.repository.security;

import com.filetransfer.shared.entity.security.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserPermissionRepository extends JpaRepository<UserPermission, UUID> {

    @Query("SELECT up FROM UserPermission up JOIN FETCH up.permission WHERE up.user.id = :userId")
    List<UserPermission> findByUserIdWithPermission(@Param("userId") UUID userId);

    @Query("SELECT up.permission.name FROM UserPermission up " +
           "WHERE up.user.id = :userId AND (up.resourceId IS NULL OR up.resourceId = :resourceId)")
    List<String> findPermissionNamesForUserAndResource(
            @Param("userId") UUID userId, @Param("resourceId") UUID resourceId);

    void deleteByUserId(UUID userId);
}
