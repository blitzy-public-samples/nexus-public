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

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.maven.tasks.RemoveSnapshotsConfig;

/**
 * Facet handling the removal of snapshots from a repository.
 *
 * @since 3.0
 */
@Facet.Exposed
public interface RemoveSnapshotsFacet
    extends Facet
{

  /**
   * Delete snapshots matching this configuration, and update associated metadata in the repository.
   * 
   * <p>Implementations of this method should consider leveraging Java 21 Virtual Threads for improved
   * performance when handling I/O-bound operations during snapshot removal. Virtual Threads are particularly
   * beneficial for operations that involve file system access, database interactions, or other blocking I/O.</p>
   *
   * @param removeSnapshotsConfig {@link RemoveSnapshotsConfig}
   */
  void removeSnapshots(RemoveSnapshotsConfig removeSnapshotsConfig);
}