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
 * Selector for components in APT repository snapshots.
 * 
 * @since 3.17
 * @see Release
 * @see java.util.List
 * @see org.sonatype.nexus.repository.apt.internal.debian.Release
 * 
 * @Java21 This interface is compatible with Java 21 and can be used with Virtual Threads
 * when implementing classes perform I/O operations.
 */
public interface SnapshotComponentSelector
{
  /**
   * Get the list of architectures from the release.
   *
   * @param release the Release to extract architectures from
   * @return list of architecture strings
   */
  List<String> getArchitectures(final Release release);

  /**
   * Get the list of components from the release.
   *
   * @param release the Release to extract components from
   * @return list of component strings
   */
  List<String> getComponents(final Release release);
}