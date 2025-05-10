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
package org.sonatype.nexus.transaction;

/**
 * Marker interface for tests that specifically validate Virtual Thread behavior.
 * <p>
 * This interface is used with JUnit Jupiter's @Tag annotation to selectively run or filter
 * tests that verify Virtual Thread specific functionality, enabling focused testing of
 * concurrency behavior with the new lightweight threading model introduced in Java 21.
 *
 * @since 3.60
 */
public interface VirtualThreadTestGroup
{
  // empty
}