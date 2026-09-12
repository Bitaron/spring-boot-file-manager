package io.github.bitaron.filemanager.core;

import java.util.UUID;

/**
 * The identity recorded as having performed an action - create/update/delete/trash/purge a File
 * or Folder, mint an AccessToken (see the glossary in {@code CONTEXT.md}).
 *
 * <p>In v1 every Actor is an ApiKey, but this reference is kept as an opaque id rather than a
 * construct exclusive to ApiKey (decision #10), so a future Actor kind (e.g. a human User) can be
 * added without migrating existing {@code created_by}/{@code last_modified_by}/{@code trashed_by}
 * records - those columns carry no database-level foreign key to {@code api_key} (see ADR 0003).
 */
public record Actor(UUID id) {

    public Actor {
        if (id == null) {
            throw new IllegalArgumentException("Actor id must not be null");
        }
    }
}
