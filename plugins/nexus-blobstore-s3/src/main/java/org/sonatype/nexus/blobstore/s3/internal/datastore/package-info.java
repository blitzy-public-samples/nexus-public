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

/**
 * S3 BlobStore implementation for the datastore-based architecture.
 * <p>
 * This package contains components that are only active when the datastore feature is enabled.
 * <p>
 * Java 21 compatibility notes:
 * - This package leverages Virtual Threads for I/O-bound S3 operations when enabled
 * - Uses Java 21's enhanced concurrency model for improved S3 upload/download performance
 * - Compatible with Java 21's module system and annotation processing
 *
 * @since 3.31
 * @updated 21.0 - Updated for Java 21 compatibility
 */
@FeatureFlag(name = DATASTORE_ENABLED)
package org.sonatype.nexus.blobstore.s3.internal.datastore;

import org.sonatype.nexus.common.app.FeatureFlag;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;
