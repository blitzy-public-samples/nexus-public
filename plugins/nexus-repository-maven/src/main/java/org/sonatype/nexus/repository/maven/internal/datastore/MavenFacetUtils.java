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
package org.sonatype.nexus.repository.maven.internal.datastore;

import javax.annotation.Nonnull;

import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;

import static org.sonatype.nexus.repository.maven.internal.Attributes.P_BASE_VERSION;
import static org.sonatype.nexus.repository.maven.internal.Constants.SNAPSHOT_VERSION_SUFFIX;

/**
 * Maven facet utilities for datastore implementation.
 *
 * @since 3.31
 * @see Java 21 compatible
 */
public final class MavenFacetUtils
{
  private MavenFacetUtils() {
    // Empty constructor to prevent instantiation
  }

  /**
   * Determines if a given Component is a snapshot version.
   *
   * @param component the component to check, must not be null
   * @return true if the component is a snapshot version, false otherwise
   * @throws NullPointerException if component is null
   */
  public static boolean isSnapshot(@Nonnull final Component component) {
    String baseVersion = (String) component.attributes().child(Maven2Format.NAME).get(P_BASE_VERSION);
    return baseVersion != null && baseVersion.endsWith(SNAPSHOT_VERSION_SUFFIX);
  }
}