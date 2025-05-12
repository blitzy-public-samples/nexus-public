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

import org.sonatype.nexus.repository.view.Content;

import static org.sonatype.nexus.repository.apt.internal.AptMimeTypes.BZIP;
import static org.sonatype.nexus.repository.apt.internal.AptMimeTypes.GZIP;
import static org.sonatype.nexus.repository.apt.internal.AptMimeTypes.SIGNATURE;
import static org.sonatype.nexus.repository.apt.internal.AptMimeTypes.TEXT;
import static org.sonatype.nexus.repository.apt.internal.AptMimeTypes.XZ;

/**
 * Represents an item in an APT repository snapshot, pairing a content specifier with its content.
 * 
 * <p>This class is immutable and thread-safe, leveraging Java 21 records for its data structures.</p>
 *
 * @since 3.17
 */
public record SnapshotItem(ContentSpecifier specifier, Content content)
{
  /**
   * Defines the role of a snapshot item, which determines its MIME type and purpose in the APT repository.
   * Each role is associated with a specific MIME type from {@link org.sonatype.nexus.repository.apt.internal.AptMimeTypes}.
   */
  public enum Role
  {
    /**
     * The main Release index file (Release).
     */
    RELEASE_INDEX(TEXT),
    
    /**
     * An inline Release index file.
     */
    RELEASE_INLINE_INDEX(TEXT),
    
    /**
     * Raw package index file (Packages).
     */
    PACKAGE_INDEX_RAW(TEXT),
    
    /**
     * Release signature file (Release.gpg).
     */
    RELEASE_SIG(SIGNATURE),
    
    /**
     * Gzip-compressed package index file (Packages.gz).
     */
    PACKAGE_INDEX_GZ(GZIP),
    
    /**
     * Bzip2-compressed package index file (Packages.bz2).
     */
    PACKAGE_INDEX_BZ2(BZIP),
    
    /**
     * XZ-compressed package index file (Packages.xz).
     */
    PACKAGE_INDEX_XZ(XZ);

    private final String mimeType;

    Role(final String mimeType) {
      this.mimeType = mimeType;
    }

    /**
     * Returns the MIME type associated with this role.
     *
     * @return the MIME type string
     */
    public String getMimeType() {
      return mimeType;
    }
  }

  /**
   * Specifies the path and role of a snapshot item.
   * 
   * @param path the repository path of the item
   * @param role the role of the item, which determines its MIME type
   */
  public record ContentSpecifier(String path, Role role) {}
  
  /**
   * Creates a new SnapshotItem with the given specifier and content.
   * 
   * @param specifier the content specifier defining the path and role
   * @param content the actual content of the item
   */
  public SnapshotItem {
    // Validate parameters
    if (specifier == null) {
      throw new NullPointerException("specifier cannot be null");
    }
    if (content == null) {
      throw new NullPointerException("content cannot be null");
    }
  }
}
