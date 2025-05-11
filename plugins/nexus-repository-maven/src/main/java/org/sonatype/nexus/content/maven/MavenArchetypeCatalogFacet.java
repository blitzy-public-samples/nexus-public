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
package org.sonatype.nexus.content.maven;

import java.io.IOException;

import org.sonatype.nexus.repository.Facet;

/**
 * Facet for rebuilding maven archetype catalog.
 *
 * This interface is compatible with Java 21 and can be implemented by classes
 * leveraging Java 21 features such as Virtual Threads for improved I/O performance
 * during catalog rebuilding operations.
 *
 * @since 3.25
 */
@Facet.Exposed
public interface MavenArchetypeCatalogFacet
    extends Facet
{
  /**
   * Rebuilds the maven archetype catalog.
   * 
   * Implementations may leverage Java 21 Virtual Threads for improved I/O performance
   * when performing catalog rebuilding operations.
   *
   * @throws IOException if an I/O error occurs during catalog rebuilding
   */
  void rebuildArchetypeCatalog() throws IOException;
}