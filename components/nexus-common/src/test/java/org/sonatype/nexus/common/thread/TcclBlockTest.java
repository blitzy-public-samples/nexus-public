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
package org.sonatype.nexus.common.thread;

import java.security.SecureClassLoader;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link TcclBlock}.
 * 
 * See also {@code TcclBlockVirtualThreadTest} for Virtual Thread compatibility testing.
 */
public class TcclBlockTest
    extends TestSupport
{
  @Test
  void beginAndRestoreClassLoaderWorksCorrectly() {
    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    ClassLoader classLoader = new SecureClassLoader(getClass().getClassLoader())
    {
    };

    try (TcclBlock tccl = TcclBlock.begin(classLoader)) {
      assertThat("Context ClassLoader should be set to the new ClassLoader", thread.getContextClassLoader(), is(classLoader));
    }
    assertThat("Context ClassLoader should be restored to original", thread.getContextClassLoader(), is(original));
  }
  
  @Test
  void nullClassLoaderHandlingThrowsException() {
    assertThrows(NullPointerException.class, () -> {
      TcclBlock.begin((ClassLoader) null);
    }, "TcclBlock.begin should throw NullPointerException when ClassLoader is null");
  }
}