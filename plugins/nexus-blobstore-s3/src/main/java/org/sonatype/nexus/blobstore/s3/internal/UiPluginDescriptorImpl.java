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
package org.sonatype.nexus.blobstore.s3.internal;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.rapture.UiPluginDescriptor;
import org.sonatype.nexus.rapture.UiPluginDescriptorSupport;

import org.eclipse.sisu.Priority;

// Java 21 String Template imports
import static java.lang.StringTemplate.STR;

/**
 * Rapture {@link UiPluginDescriptor} for {@code nexus-blobstore-s3}.
 * 
 * This implementation is compatible with Java 21 and OSGi/Karaf 4.3.9+ runtime environment.
 * It provides UI plugin configuration for the S3 BlobStore feature.
 * 
 * Registered as an OSGi service component with the highest priority to ensure proper loading order.
 * This component integrates the S3 BlobStore UI elements into the Nexus Repository Manager interface.
 *
 * @since 3.17
 * @see org.sonatype.nexus.blobstore.s3.internal.S3BlobStore
 */
@Named
@Singleton
@Priority(Integer.MAX_VALUE)
public class UiPluginDescriptorImpl
    extends UiPluginDescriptorSupport
{
  /**
   * Constructs a new UI plugin descriptor for the S3 BlobStore feature.
   * Configures the plugin with appropriate namespace and configuration class.
   * 
   * Implementation is compatible with Java 21 virtual threads and modern language features.
   */
  @Inject
  public UiPluginDescriptorImpl() {
    // Using String Template for plugin ID (though simple in this case, demonstrates Java 21 compatibility)
    super(STR."nexus-blobstore-s3");
    setHasStyle(false);
    setNamespace("NX.s3blobstore");
    setConfigClassName("NX.s3blobstore.app.PluginConfig");
  }
}