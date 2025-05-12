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
 * APT repository datastore implementation.
 * <p>
 * This package contains the datastore implementation for APT repositories.
 * The implementation is compatible with Java 21 and leverages the following features:
 * <ul>
 *   <li>Virtual Threads for I/O operations when enabled via feature flag</li>
 *   <li>Pattern Matching for type-safe data handling</li>
 *   <li>Enhanced type checking with Java 21 compiler improvements</li>
 * </ul>
 *
 * @since 3.30
 * @updated 21.0 - Updated for Java 21 compatibility
 */
@FeatureFlag(name = DATASTORE_ENABLED)
package org.sonatype.nexus.repository.apt.datastore;

import org.sonatype.nexus.common.app.FeatureFlag;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;
