package io.github.bitaron.filemanager.core.file;

import java.io.InputStream;

/**
 * A File's metadata paired with a live content stream, as returned by
 * {@link FileService#fetchContent}. The caller is responsible for closing {@code content}.
 */
public record FileContent(File file, InputStream content) {
}
