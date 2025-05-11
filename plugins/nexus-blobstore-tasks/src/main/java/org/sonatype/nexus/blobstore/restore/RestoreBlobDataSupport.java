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

import java.util.Objects;

/**
 * Support class that wraps a {@link RestoreBlobData} object, ensuring it's not null
 * and providing safe access to it during blob restoration operations.
 *
 * @since 3.14
 */
public class RestoreBlobDataSupport
{
  private final RestoreBlobData blobData;

  /**
   * Creates a new instance with the specified blob data.
   *
   * @param blobData the blob data to wrap (must not be null)
   * @throws NullPointerException if blobData is null
   */
  public RestoreBlobDataSupport(final RestoreBlobData blobData) {
    this.blobData = Objects.requireNonNull(blobData, "Blob data cannot be null");
  }

  /**
   * Returns the wrapped blob data.
   *
   * @return the non-null blob data
   */
  public RestoreBlobData getBlobData() {
    return blobData;
  }
}
