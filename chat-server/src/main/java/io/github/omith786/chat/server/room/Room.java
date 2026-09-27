package io.github.omith786.chat.server.room;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A public chat room (group chat) that any signed-in user can join. */
@Entity
@Table(name = "room")
public class Room {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(name = "created_by", nullable = false, length = 20)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Room() {
        // for JPA
    }

    public Room(String id, String name, String description, String createdBy, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
