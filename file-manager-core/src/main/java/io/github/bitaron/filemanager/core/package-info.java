/**
 * Domain model and engine for the file-management service: {@code Tenant}, {@code Folder},
 * {@code File}, {@code Visibility}, {@code AccessToken}, {@code Trash} (see the glossary in
 * {@code CONTEXT.md}).
 *
 * <p>This module carries no Spring Web or transport dependency, so it stays usable from both
 * Embedded Mode (via the autoconfigure/starter modules) and the Standalone Service's controllers.
 */
package io.github.bitaron.filemanager.core;
