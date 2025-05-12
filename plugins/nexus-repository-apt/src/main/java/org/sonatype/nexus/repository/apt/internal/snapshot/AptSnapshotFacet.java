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

import java.io.IOException;

import jakarta.annotation.Nullable;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.view.Content;

/**
 * Facet for managing APT repository snapshots.
 *
 * @since 3.17
 * @since 3.60 Compatible with Java 21 and Virtual Threads
 */
@Facet.Exposed
public interface AptSnapshotFacet
    extends Facet
{
  /**
   * Determines if a file at the given path is snapshotable.
   *
   * @param path the path to check
   * @return true if the file is snapshotable, false otherwise
   */
  boolean isSnapshotableFile(final String path);

  /**
   * Creates a snapshot with the given ID using the specified component selector.
   * This method is I/O-bound and benefits from execution on a Virtual Thread in Java 21.
   *
   * @param id the ID for the new snapshot
   * @param spec the component selector to use for the snapshot
   * @throws IOException if an I/O error occurs during snapshot creation
   */
  void createSnapshot(final String id, final SnapshotComponentSelector spec) throws IOException;

  /**
   * Retrieves a file from a snapshot with the given ID at the specified path.
   * This method is I/O-bound and benefits from execution on a Virtual Thread in Java 21.
   *
   * @param id the ID of the snapshot
   * @param path the path of the file to retrieve
   * @return the content of the file, or null if not found
   * @throws IOException if an I/O error occurs during file retrieval
   */
  @Nullable
  Content getSnapshotFile(final String id, final String path) throws IOException;

  /**
   * Deletes a snapshot with the given ID.
   * This method is I/O-bound and benefits from execution on a Virtual Thread in Java 21.
   *
   * @param id the ID of the snapshot to delete
   * @throws IOException if an I/O error occurs during snapshot deletion
   */
  void deleteSnapshot(final String id) throws IOException;
}