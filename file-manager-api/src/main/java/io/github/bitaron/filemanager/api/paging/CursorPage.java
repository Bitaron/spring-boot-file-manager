package io.github.bitaron.filemanager.api.paging;

import java.util.List;
import java.util.UUID;

/**
 * One page of a cursor-paginated list response (ADR 0004: cursor pagination over the UUIDv7 PK,
 * default page size 50, max 200). {@code nextCursor} is {@code null} once there are no more
 * items; pass it back as the {@code cursor} query parameter to fetch the following page.
 */
public record CursorPage<T>(List<T> items, UUID nextCursor) {
}
