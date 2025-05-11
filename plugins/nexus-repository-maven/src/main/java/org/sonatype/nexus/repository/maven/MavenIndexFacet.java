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
package org.sonatype.nexus.repository.maven;

import java.io.IOException;
import java.time.Instant;

import jakarta.annotation.Nullable;

import org.sonatype.nexus.repository.Facet;

/**
 * Maven2 specific index facet responsible to generate index (for hosted and group repositories).
 *
 * @since 3.0
 */
@Facet.Exposed
public interface MavenIndexFacet
    extends Facet
{
  /**
   * Returns time when index was last published on this repository, or {@code null} if index is not published for
   * whatever reason.
   * 
   * @return the instant when the index was last published, or null if not published
   */
  @Nullable
  Instant lastPublished() throws IOException;

  /**
   * Publishes Maven Indexer indexes repository for downstream consumption.
   * 
   * <p>In Java 21, this operation can benefit from Virtual Threads for improved I/O performance
   * when handling large index files.</p>
   */
  void publishIndex() throws IOException;

  /**
   * Removes published Maven Indexer indexes from repository (or cache, if proxy).
   */
  void unpublishIndex() throws IOException;
}