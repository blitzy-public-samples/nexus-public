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
package org.sonatype.nexus.testsuite.testsupport;

import org.sonatype.nexus.testsuite.testsupport.fixtures.RepositoryRule;

/**
 * Support class for repository format ITs.
 * 
 * <p>This class has been updated for Java 21 compatibility, including support for JUnit Jupiter 5.10.1
 * and optimized repository provisioning logic that can leverage Virtual Threads when available.</p>
 *
 * @deprecated Please write new tests as part of the {@link ITSupport} hierarchy which provides enhanced
 *             support for Java 21 features including pattern matching and virtual threads
 */
@Deprecated
public abstract class RepositoryITSupport
    extends GenericRepositoryITSupport<RepositoryRule>
{
  /**
   * Creates a repository rule for managing test repositories.
   * 
   * @return a new {@link RepositoryRule} instance configured with the repository manager
   */
  @Override
  protected RepositoryRule createRepositoryRule() {
    // Using lambda for provider implementation - compatible with Virtual Threads in Java 21
    return new RepositoryRule(() -> repositoryManager);
  }
}