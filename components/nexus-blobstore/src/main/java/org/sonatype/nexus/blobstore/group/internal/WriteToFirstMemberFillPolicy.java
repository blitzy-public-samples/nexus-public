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
package org.sonatype.nexus.blobstore.group.internal;

import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.blobstore.group.FillPolicy;

/**
 * {@link FillPolicy} that writes to first blobstore in group.
 * <p>
 * This implementation selects the first available and writable blob store from the group's members.
 * The selection process is performed using a non-blocking stream pipeline that is optimized for
 * concurrent execution in both platform thread and Virtual Thread environments.
 * <p>
 * Thread Safety: This implementation is thread-safe and can be safely used in concurrent contexts,
 * including with Java 21 Virtual Threads. The stream operations are stateless and do not maintain
 * any shared mutable state between invocations.
 *
 * @since 3.14
 */
@Named(WriteToFirstMemberFillPolicy.TYPE)
public class WriteToFirstMemberFillPolicy
    extends ComponentSupport
    implements FillPolicy
{

  public static final String TYPE = "writeToFirst";

  private static final String NAME = "Write to First";

  @Override
  public String getName() {
    return NAME;
  }

  /**
   * Chooses the first available and writable blob store from the group's members.
   * <p>
   * This method uses an optimized stream pipeline that is compatible with Java 21 Virtual Threads.
   * The implementation is non-blocking and efficiently handles concurrent invocations without
   * maintaining shared mutable state.
   * <p>
   * The filters are applied in order of increasing computational cost:
   * 1. First check if the blob store is writable (typically a fast boolean check)
   * 2. Then check if storage is available (may involve more complex I/O operations)
   *
   * @param blobStoreGroup the blob store group containing member blob stores
   * @param headers optional request headers (not used in this implementation)
   * @return the first available and writable blob store, or null if none is found
   */
  @Override
  @Nullable
  public BlobStore chooseBlobStore(final BlobStoreGroup blobStoreGroup, final Map<String, String> headers) {
    return blobStoreGroup
        .getMembers()
        .stream()
        .filter(BlobStore::isWritable)
        .filter(BlobStore::isStorageAvailable)
        .findFirst()
        .orElse(null);
  }
}