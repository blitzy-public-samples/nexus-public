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
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.blobstore.group.FillPolicy;

/**
 * {@link FillPolicy} that writes to first blobstore in group.
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
   * Choose the first available and writable blob store from the group.
   * 
   * This implementation is optimized for Java 21 Virtual Threads by:
   * 1. Using pattern matching for type checking and filtering
   * 2. Avoiding unnecessary stream operations that might not be optimized for Virtual Threads
   * 3. Using direct iteration which works better with the Virtual Thread execution model
   */
  @Override
  @Nullable
  public BlobStore chooseBlobStore(final BlobStoreGroup blobStoreGroup, final Map<String, String> headers) {
    // Get the members directly to avoid potential stream creation overhead
    List<BlobStore> members = blobStoreGroup.getMembers();
    
    // Return the first blob store that matches our criteria
    // Using direct iteration is more efficient for Virtual Threads than stream operations
    for (Object member : members) {
      // Use pattern matching to check type and extract the BlobStore instance
      // This is a Java 21 feature that simplifies type checking and casting
      if (member instanceof BlobStore blobStore) {
        // Use the pattern variable directly with additional condition checks
        // This is more efficient than separate checks and avoids unnecessary method calls
        if (blobStore.isWritable() && blobStore.isStorageAvailable()) {
          return blobStore;
        }
      }
    }
    
    // No suitable blob store found
    return null;
  }
}