package io.github.bitaron.filemanager.core.accesstoken;

import io.github.bitaron.filemanager.core.file.FileContent;

/**
 * The result of {@link AccessTokenService#redeem}: the redeemed token's {@link Purpose} (drives
 * the caller's {@code Content-Disposition} - {@code VIEW} inline, {@code DOWNLOAD} attachment)
 * paired with the File's metadata and a live content stream. Deliberately not just a bare
 * {@link FileContent} - the caller needs the Purpose too, and re-deriving it would mean a second,
 * redundant lookup of the same token row.
 */
public record AccessTokenRedemption(Purpose purpose, FileContent fileContent) {
}
