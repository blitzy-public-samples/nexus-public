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
 * S3 BlobStore metrics service implementation for datastore-backed Nexus repositories.
 * <p>
 * This package provides datastore-specific implementations for S3 BlobStore metrics.
 * When the DATASTORE_ENABLED feature flag is active, components in this package are
 * discovered and wired into the application.
 * <p>
 * With Java 21, this package benefits from:
 * - Virtual Threads for improved I/O performance with S3 operations
 * - Enhanced security features for AWS credential handling
 * - Improved concurrency for metrics collection and persistence
 *
 * @since 3.20
 * @updated 21.0 - Updated for Java 21 compatibility
 */
@FeatureFlag(name = DATASTORE_ENABLED)
package org.sonatype.nexus.blobstore.s3.internal.datastore;

import org.sonatype.nexus.common.app.FeatureFlag;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;