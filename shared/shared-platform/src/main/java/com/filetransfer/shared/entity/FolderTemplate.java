package com.filetransfer.shared.entity;

import com.filetransfer.shared.dto.FolderDefinition;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "folder_templates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FolderTemplate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "built_in", nullable = false)
    @Builder.Default
    private boolean builtIn = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<FolderDefinition> folders;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
