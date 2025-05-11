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
 * Maven content package for Nexus Repository Manager.
 * <p>
 * This package is enabled via the DATASTORE_ENABLED feature flag.
 * <p>
 * Java 21 compatibility note: The feature flag annotation usage in this package
 * is fully compatible with Java 21. The annotation processing mechanism remains
 * unchanged in Java 21, ensuring consistent behavior with previous versions.
 * <p>
 * When using this package with Java 21, you can leverage new language features such as:
 * - Virtual Threads for improved I/O operations
 * - Pattern Matching for more concise type checking
 * - Record Patterns for simplified data extraction
 * - String Templates for more readable string formatting
 */
@FeatureFlag(name = DATASTORE_ENABLED)

package org.sonatype.nexus.content.maven;

import org.sonatype.nexus.common.app.FeatureFlag;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;