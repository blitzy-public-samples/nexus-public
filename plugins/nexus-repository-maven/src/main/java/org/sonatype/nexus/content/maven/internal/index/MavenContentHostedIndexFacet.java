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

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.maven.MavenIndexFacet;
import org.sonatype.nexus.repository.maven.internal.MavenIndexPublisher;
import org.sonatype.nexus.repository.maven.internal.filter.DuplicateDetectionStrategy;
import org.sonatype.nexus.repository.maven.internal.filter.DuplicateDetectionStrategyProvider;
import org.sonatype.nexus.repository.maven.internal.hosted.MavenHostedIndexFacet;

import org.apache.maven.index.reader.Record;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Hosted implementation of {@link MavenIndexFacet} with Java 21 enhancements.
 *
 * <p>This implementation leverages Java 21 features such as Virtual Threads for I/O operations
 * through the parent class {@link MavenContentIndexFacetSupport}. The index publishing operation
 * is executed on a virtual thread to improve performance for this I/O-bound task.</p>
 *
 * @since 3.26
 */
@Named
public class MavenContentHostedIndexFacet
    extends MavenContentIndexFacetSupport
    implements MavenHostedIndexFacet
{
  private final DuplicateDetectionStrategyProvider duplicateDetectionStrategyProvider;

  /**
   * Constructor with dependency injection support for Java 21 environment.
   *
   * @param duplicateDetectionStrategyProvider provider for duplicate detection strategies
   * @param mavenIndexPublisher publisher for Maven indexes
   */
  @Inject
  public MavenContentHostedIndexFacet(
      final DuplicateDetectionStrategyProvider duplicateDetectionStrategyProvider,
      final MavenIndexPublisher mavenIndexPublisher)
  {
    super(mavenIndexPublisher);
    this.duplicateDetectionStrategyProvider = checkNotNull(duplicateDetectionStrategyProvider);
  }

  /**
   * Implementation of the abstract method from the parent class.
   * This method is called by the parent's {@link MavenContentIndexFacetSupport#publishIndex()}
   * which executes this I/O-bound operation on a virtual thread for improved performance.
   *
   * @throws IOException if an I/O error occurs during publishing
   */
  @Override
  protected void doPublishIndex() throws IOException {
    try (var strategy = duplicateDetectionStrategyProvider.get()) {
      mavenIndexPublisher.publishHostedIndex(getRepository(), strategy);
    }
    catch (Exception e) {
      // Use pattern matching for exception handling (Java 21 feature)
      if (e instanceof IOException ioe) {
        throw ioe;
      }
      throw new IOException(STR."Error publishing hosted index for \{getRepository().getName()}", e);
    }
  }
}