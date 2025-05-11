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
 * Maven repository table-based search implementation.
 * <p>
 * This package provides table-based search functionality for Maven repositories.
 * <p>
 * <strong>Java 21 Requirement:</strong> This implementation requires Java 21 or later
 * and leverages Java 21 features such as Virtual Threads for improved search performance
 * and concurrency when handling large result sets.
 *
 * @since 3.60.0
 * @requires Java 21
 */
@FeatureFlag(name = DATASTORE_TABLE_SEARCH)
package org.sonatype.nexus.content.maven.internal.search.table;

import org.sonatype.nexus.common.app.FeatureFlag;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_TABLE_SEARCH;