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
 * Maven repository format.
 *
 * This package and its subpackages provide Maven repository format support, optimized for Java 21 runtime.
 * The implementation leverages Java 21 features including:
 * <ul>
 *   <li>Virtual Threads for improved concurrency in repository operations</li>
 *   <li>Pattern Matching for more expressive and maintainable code</li>
 *   <li>Enhanced security features for repository content protection</li>
 * </ul>
 *
 * @since 3.0
 */
package org.sonatype.nexus.repository.maven;