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

import jakarta.annotation.Nonnull;

import org.sonatype.nexus.repository.Facet;

/**
 * Maven facet, present on all Maven repositories.
 *
 * @since 3.0
 * @see Facet
 */
@Facet.Exposed
public interface MavenFacet
    extends Facet
{
  /**
   * Returns the format specific {@link MavenPathParser}.
   *
   * @return the Maven path parser
   */
  @Nonnull
  MavenPathParser getMavenPathParser();

  /**
   * Returns the version policy in effect for this repository.
   *
   * @return the version policy
   */
  @Nonnull
  VersionPolicy getVersionPolicy();

  /**
   * Returns the layout policy in effect for this repository.
   *
   * @return the layout policy
   */
  LayoutPolicy layoutPolicy();

  /**
   * Checks if an asset exists at the given path.
   *
   * @param path of the asset to check
   * @return true if it exists
   * @since 3.14
   */
  boolean exists(final MavenPath path);
}