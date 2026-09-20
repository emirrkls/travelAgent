package com.emirrkls.phokarta.backend.domain.entity;

import com.emirrkls.phokarta.backend.domain.model.ConversationEntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "experience_conversation_entries")
public class ExperienceConversationEntry {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experience_id", nullable = false)
    private Visit experience;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private ConversationEntryType entryType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_entry_id")
    private ExperienceConversationEntry parent;

    @Column(nullable = false, length = 1000)
    private String body;

    @Column(name = "client_mutation_id", nullable = false)
    private UUID clientMutationId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected ExperienceConversationEntry() {
    }

    public ExperienceConversationEntry(
            UUID id, Visit experience, User author, ConversationEntryType entryType,
            ExperienceConversationEntry parent, String body, UUID clientMutationId,
            OffsetDateTime now) {
        this.id = id;
        this.experience = experience;
        this.author = author;
        this.entryType = entryType;
        this.parent = parent;
        this.body = body;
        this.clientMutationId = clientMutationId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Visit getExperience() { return experience; }
    public User getAuthor() { return author; }
    public ConversationEntryType getEntryType() { return entryType; }
    public ExperienceConversationEntry getParent() { return parent; }
    public String getBody() { return body; }
    public UUID getClientMutationId() { return clientMutationId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getEditedAt() { return editedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }

    public void edit(String updatedBody, OffsetDateTime now) {
        if (!body.equals(updatedBody)) {
            body = updatedBody;
            updatedAt = now;
            editedAt = now;
        }
    }

    public void delete(OffsetDateTime now) {
        if (deletedAt == null) {
            deletedAt = now;
            updatedAt = now;
        }
    }
}
