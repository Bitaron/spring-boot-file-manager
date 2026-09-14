package io.github.bitaron.filemanager.core.trash;

import io.github.bitaron.filemanager.core.file.File;
import io.github.bitaron.filemanager.core.folder.Folder;

/**
 * One row of {@link TrashService#list}'s unified listing, discriminating which aggregate a
 * trashed item actually is (issue #37, decision #17) - mirrors {@code core.file.FileContent}'s
 * existing record-wrapper precedent for a small, closed set of shapes.
 */
public sealed interface TrashItem {

    /** A trashed {@link Folder}, own {@code trashedAt} set. */
    record TrashedFolder(Folder folder) implements TrashItem {
    }

    /** A trashed {@link File}, own {@code trashedAt} set. */
    record TrashedFile(File file) implements TrashItem {
    }
}
