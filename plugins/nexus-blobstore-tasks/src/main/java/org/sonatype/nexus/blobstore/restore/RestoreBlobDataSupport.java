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
package org.sonatype.nexus.blobstore.restore;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Support class that wraps a {@link RestoreBlobData} instance and ensures it is not null.
 * This class provides a safe way to access blob data during the restore process.
 *
 * @since 3.14
 */
public class RestoreBlobDataSupport
{
  private final RestoreBlobData blobData;

  /**
   * Constructs a new RestoreBlobDataSupport instance.
   *
   * @param blobData the blob data to wrap (must not be null)
   * @throws NullPointerException if blobData is null
   */
  public RestoreBlobDataSupport(final RestoreBlobData blobData) {
    this.blobData = checkNotNull(blobData, STR."RestoreBlobData cannot be null");
  }

  /**
   * Returns the wrapped RestoreBlobData instance.
   *
   * @return the non-null RestoreBlobData instance
   */
  public RestoreBlobData getBlobData() {
    return blobData;
  }
}
