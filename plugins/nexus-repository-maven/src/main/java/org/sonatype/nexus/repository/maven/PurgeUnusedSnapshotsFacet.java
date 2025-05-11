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

/**
 * Facet for purging unused Maven snapshots.
 * <p>
 * Implementations of this facet can leverage Java 21 features such as Virtual Threads
 * for improved performance when purging unused snapshots, as this operation is typically
 * I/O-bound and would benefit from the high-concurrency capabilities of Virtual Threads.
 *
 * @since 3.0
 */
@Facet.Exposed
public interface PurgeUnusedSnapshotsFacet
    extends Facet
{
  /**
   * Purges snapshots that were not used/accessed for a number of days.
   * <p>
   * This operation may involve significant I/O operations and can benefit from
   * Java 21's Virtual Threads when implemented by facet providers.
   *
   * @param numberOfDays number of days from the moment the method is invoked. Must be > 0.
   */
  void purgeUnusedSnapshots(int numberOfDays);
}