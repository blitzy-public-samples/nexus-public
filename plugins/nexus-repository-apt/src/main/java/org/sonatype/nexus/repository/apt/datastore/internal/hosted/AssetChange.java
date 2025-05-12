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
package org.sonatype.nexus.repository.apt.datastore.internal.hosted;

import org.sonatype.nexus.repository.apt.internal.hosted.AssetAction;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;

/**
 * Helper class which helps to connect asset and action performed under it.
 * Implemented as a Java Record for immutability and concise data representation.
 *
 * @since 3.31
 */
public record AssetChange(AssetAction action, FluentAsset asset) {
  // Record automatically provides constructor, accessors, equals, hashCode, and toString
}