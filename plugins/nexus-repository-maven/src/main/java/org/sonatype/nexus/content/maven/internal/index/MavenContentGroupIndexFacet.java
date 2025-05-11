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
package org.sonatype.nexus.content.maven.internal.index;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.maven.MavenIndexFacet;
import org.sonatype.nexus.repository.maven.internal.MavenIndexPublisher;
import org.sonatype.nexus.repository.maven.internal.filter.DuplicateDetectionStrategy;
import org.sonatype.nexus.repository.maven.internal.filter.DuplicateDetectionStrategyProvider;

import org.apache.maven.index.reader.Record;

/**
 * Group implementation of {@link MavenIndexFacet}.
 * <p>
 * This implementation leverages Java 21 features:
 * <ul>
 *   <li>Virtual Threads for I/O-bound index publishing operations</li>
 *   <li>Pattern Matching for instanceof when retrieving facets</li>
 * </ul>
 *
 * @since 3.26
 */
@Named
public class MavenContentGroupIndexFacet
    extends MavenContentIndexFacetSupport
    implements MavenIndexFacet
{
  private final DuplicateDetectionStrategyProvider duplicateDetectionStrategyProvider;

  @Inject
  public MavenContentGroupIndexFacet(
      final MavenIndexPublisher mavenIndexPublisher,
      final DuplicateDetectionStrategyProvider duplicateDetectionStrategyProvider) {
    super(mavenIndexPublisher);
    this.duplicateDetectionStrategyProvider = duplicateDetectionStrategyProvider;
  }

  /**
   * Publishes the Maven index for this group repository.
   * <p>
   * This method uses Java 21 Virtual Threads to handle the I/O-bound index publishing operation,
   * which improves scalability by reducing the number of platform threads needed for concurrent operations.
   *
   * @throws IOException if an error occurs during index publishing
   */
  @Override
  public void publishIndex() throws IOException {
    try (DuplicateDetectionStrategy<Record> strategy = duplicateDetectionStrategyProvider.get()) {
      // Execute the I/O-bound index publishing operation on a virtual thread
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<?> future = executor.submit(() -> {
          try {
            // Use pattern matching for instanceof with the GroupFacet
            if (var facet = facet(GroupFacet.class); facet instanceof GroupFacet groupFacet) {
              mavenIndexPublisher.publishGroupIndex(getRepository(), groupFacet.leafMembers(), strategy);
            } else {
              throw new IOException(STR."GroupFacet not available for repository: \{getRepository().getName()}");
            }
            return null;
          } catch (IOException e) {
            throw new RuntimeException(STR."Failed to publish group index for repository: \{getRepository().getName()}", e);
          }
        });
        
        try {
          future.get(); // Wait for the virtual thread to complete
        } catch (Exception e) {
          Throwable cause = e.getCause();
          if (cause instanceof IOException ioe) {
            throw ioe;
          } else if (cause instanceof RuntimeException re && re.getCause() instanceof IOException ioe) {
            throw ioe;
          }
          throw new IOException(STR."Error during index publishing for repository: \{getRepository().getName()}", e);
        }
      }
    }
  }
}