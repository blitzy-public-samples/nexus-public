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
package org.sonatype.nexus.repository.apt.internal.snapshot;

import java.util.List;

import org.sonatype.nexus.repository.apt.internal.debian.Release;

/**
 * Implementation of {@link SnapshotComponentSelector} that includes all architectures and components
 * from a {@link Release}. This selector doesn't filter any components or architectures.
 * 
 * @since 3.17
 * @see SnapshotComponentSelector
 * @see Release
 * 
 * @Java21 This implementation is compatible with Java 21 and maintains the same behavior
 * as in previous Java versions.
 */
public class AllSnapshotComponentSelector
    implements SnapshotComponentSelector
{
  /**
   * Returns all architectures from the provided release without filtering.
   *
   * @param release the Release to extract architectures from
   * @return list of all architecture strings from the release
   */
  @Override
  public List<String> getArchitectures(final Release release) {
    return release.getArchitectures();
  }

  /**
   * Returns all components from the provided release without filtering.
   *
   * @param release the Release to extract components from
   * @return list of all component strings from the release
   */
  @Override
  public List<String> getComponents(final Release release) {
    return release.getComponents();
  }
}