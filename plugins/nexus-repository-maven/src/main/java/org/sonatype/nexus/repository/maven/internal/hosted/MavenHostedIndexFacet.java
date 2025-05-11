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
package org.sonatype.nexus.repository.maven.internal.hosted;

import java.io.IOException;

import org.sonatype.nexus.repository.maven.MavenIndexFacet;

/**
 * Maven hosted repository specific index facet responsible for generating and publishing Maven indexes.
 *
 * @since 3.0
 */
public interface MavenHostedIndexFacet
    extends MavenIndexFacet
{
  /**
   * Publishes Maven Indexer indexes for the hosted repository.
   * 
   * <p>In Java 21, this operation can benefit from Virtual Threads for improved I/O performance
   * when handling large index files.</p>
   * 
   * @throws IOException if an I/O error occurs during index publication
   */
  void publishIndex() throws IOException;
}