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
package org.sonatype.nexus.repository.maven.internal.group;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.thread.io.StreamCopier;

/**
 * Maven group repository facet.
 *
 * @since 3.0
 */
@Facet.Exposed
public interface MavenGroupFacet
    extends GroupFacet
{
  /**
   * Fetches cached content if exists, or {@code null}.
   * <p>
   * This method performs I/O operations and can benefit from being executed using virtual threads
   * when available. Configure the system property 'nexus.streamcopier.useVirtualThreads=true' to enable
   * virtual thread execution for improved performance with many concurrent operations.
   */
  @Nullable
  Content getCached(MavenPath mavenPath) throws IOException;

  /**
   * Merges and caches and returns the merged metadata. Returns {@code null} if no usable response was in passed in
   * map.
   * <p>
   * This method performs I/O operations and can benefit from being executed using virtual threads
   * when available. Configure the system property 'nexus.streamcopier.useVirtualThreads=true' to enable
   * virtual thread execution for improved performance with many concurrent operations.
   */
  @Nullable
  Content mergeAndCache(MavenPath mavenPath, Map<Repository, Response> responses) throws IOException;

  /**
   * Merges the metadata but doesn't cache it. Returns {@code null} if no usable response was in passed in map.
   * <p>
   * This method performs I/O operations and can benefit from being executed using virtual threads
   * when available. Configure the system property 'nexus.streamcopier.useVirtualThreads=true' to enable
   * virtual thread execution for improved performance with many concurrent operations.
   *
   * @since 3.13
   */
  @Nullable
  Content mergeWithoutCaching(MavenPath mavenPath, Map<Repository, Response> responses) throws IOException;

  /**
   * Functional interface for content generation.
   * <p>
   * Implementations should consider using virtual threads for I/O-bound operations
   * when processing large amounts of data.
   */
  @FunctionalInterface
  interface ContentFunction<T>
  {
    Content apply(T data, String contentType) throws IOException;
  }

  /**
   * Allows different merge methods to be used with the {@link StreamCopier}.
   * <p>
   * Implementations should consider using virtual threads for I/O-bound operations
   * when merging large metadata files. The {@link StreamCopier} can be configured to use
   * virtual threads by setting the system property 'nexus.streamcopier.useVirtualThreads=true'.
   */
  interface MetadataMerger {
    /**
     * Merges content from multiple repositories into a single output stream.
     * <p>
     * This method is I/O-bound and can benefit from virtual thread execution.
     *
     * @param outputStream the output stream to write merged content to
     * @param mavenPath the Maven path being processed
     * @param contents the contents from different repositories to merge
     */
    void merge(OutputStream outputStream, MavenPath mavenPath, LinkedHashMap<Repository, Content> contents);
  }
}