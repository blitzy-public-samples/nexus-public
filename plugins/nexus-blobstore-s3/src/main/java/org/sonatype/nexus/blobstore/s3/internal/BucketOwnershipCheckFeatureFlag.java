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
 *
 * This class is compatible with Java 21 and takes advantage of the latest JVM features for improved performance.
 * It is designed to work within the OSGi environment with Karaf 4.4.4 and Java 21 runtime.
 */
package org.sonatype.nexus.blobstore.s3.internal;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.FeatureFlags;

/**
 * Feature flag to control bucket ownership checking behavior in S3 BlobStore.
 * 
 * This class is compatible with Java 21 and follows the dependency injection pattern
 * using JSR-330 annotations for integration with the Nexus DI container.
 */

@Named
@Singleton
public class BucketOwnershipCheckFeatureFlag
{
  private final boolean isDisabled;

  /**
   * Creates a new instance with the specified feature flag setting.
   * 
   * @param isDisabled Flag indicating whether bucket ownership checking is disabled
   *                   (injected from system properties or configuration)
   */
  @Inject
  public BucketOwnershipCheckFeatureFlag(
      @Named(FeatureFlags.BLOBSTORE_OWNERSHIP_CHECK_DISABLED_NAMED) final Boolean isDisabled)
  {
    this.isDisabled = Boolean.TRUE.equals(isDisabled);
  }

  /**
   * Determines if bucket ownership checking is disabled.
   * 
   * @return true if bucket ownership checking is disabled, false otherwise
   */
  public boolean isDisabled() {
    return isDisabled;
  }
}
