/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.content;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;

/**
 * Example content facet that demonstrates Java 21 features and patterns.
 * This facet provides operations for managing example content in a repository.
 *
 * @since 3.45
 */
@Facet.Exposed
public interface ExampleContentFacet
    extends ContentFacet
{
    /**
     * Get content by path.
     *
     * @param path The path of the content to retrieve
     * @return Optional containing the Content if found, empty Optional otherwise
     * @throws IOException if an I/O error occurs
     * 
     * @apiNote Implementations should leverage Java 21 Virtual Threads for I/O operations
     */
    Optional<Content> get(@Nonnull String path) throws IOException;

    /**
     * Store content at the specified path.
     *
     * @param path The path where content should be stored
     * @param content The content to store
     * @return The stored Content
     * @throws IOException if an I/O error occurs
     * 
     * @apiNote Implementations should leverage Java 21 Virtual Threads for I/O operations
     */
    Content put(@Nonnull String path, @Nonnull Payload content) throws IOException;

    /**
     * Delete content at the specified path.
     *
     * @param path The path of the content to delete
     * @return true if content was deleted, false if not found
     * @throws IOException if an I/O error occurs
     * 
     * @apiNote Implementations should leverage Java 21 Virtual Threads for I/O operations
     */
    boolean delete(@Nonnull String path) throws IOException;

    /**
     * Delete multiple content items by their paths.
     *
     * @param paths The list of paths to delete
     * @return true if all content items were deleted, false otherwise
     * 
     * @apiNote Implementations should leverage Java 21 Virtual Threads for I/O operations
     */
    boolean delete(@Nonnull List<String> paths);

    /**
     * Check if content exists at the specified path.
     *
     * @param path The path to check
     * @return true if content exists, false otherwise
     * 
     * @apiNote Implementations should leverage Java 21 Virtual Threads for I/O operations
     */
    boolean exists(@Nonnull String path);

    /**
     * Find components by name pattern.
     *
     * @param namePattern The pattern to match component names against
     * @param limit Maximum number of components to return
     * @param continuationToken Optional token to continue from a previous request
     * @return Collection of components and the next continuation token
     * 
     * @apiNote Implementations should leverage Java 21 Pattern Matching for type checking and Virtual Threads for database operations
     */
    Continuation<FluentComponent> findComponentsByName(
        @Nonnull String namePattern,
        int limit,
        @Nullable String continuationToken);

    /**
     * Find assets by path pattern.
     *
     * @param pathPattern The pattern to match asset paths against
     * @param limit Maximum number of assets to return
     * @param continuationToken Optional token to continue from a previous request
     * @return Collection of assets and the next continuation token
     * 
     * @apiNote Implementations should leverage Java 21 Pattern Matching for type checking and Virtual Threads for database operations
     */
    Continuation<Asset> findAssetsByPath(
        @Nonnull String pathPattern,
        int limit,
        @Nullable String continuationToken);

    /**
     * Create a component and asset for a given path without attaching a blob to the asset.
     * This is primarily used when the blob will be hard linked to the asset afterwards.
     *
     * @param path The path for the component and asset
     * @return The created asset
     * 
     * @apiNote Implementations should leverage Java 21 Virtual Threads for database operations
     */
    FluentAsset createComponentAndAsset(@Nonnull String path);

    /**
     * Copy a component from another repository to the current repository.
     *
     * @param source Component to copy
     * @return A new component copied from the source to the current repository
     * 
     * @apiNote Implementations should leverage Java 21 Virtual Threads for I/O operations and Pattern Matching for type checking
     */
    FluentComponent copy(@Nonnull Component source);

    /**
     * Get all unique tags from components in the repository.
     *
     * @return Set of unique tags
     * 
     * @apiNote Implementations should leverage Java 21 Virtual Threads for database operations and Sequenced Collections for processing results
     */
    Set<String> getAllTags();

    /**
     * Find components by tag.
     *
     * @param tag The tag to search for
     * @return Stream of components with the specified tag
     * 
     * @apiNote Implementations should leverage Java 21 Virtual Threads for database operations and String Templates for logging
     */
    Stream<FluentComponent> findComponentsByTag(@Nonnull String tag);

    /**
     * Process component metadata using record patterns.
     *
     * @param component The component to process
     * @return Processed metadata as a string
     * 
     * @apiNote Implementations should leverage Java 21 Record Patterns for extracting and processing component metadata
     */
    String processComponentMetadata(@Nonnull Component component);
}