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
package org.sonatype.nexus.extdirect.internal;

import java.util.concurrent.Callable;

import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.security.UserIdMdcHelper;

import com.google.inject.servlet.ServletScopes;
import com.softwarementors.extjs.djn.servlet.ssm.SsmJsonRequestProcessorThread;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;

import static com.google.common.base.Preconditions.checkState;

/**
 * An {@link SsmJsonRequestProcessorThread} that binds the thread to Shiro subject as well as setting user id in
 * MDC. Uses Java 21 Virtual Threads for improved scalability and reduced resource consumption.
 *
 * @since 3.0
 */
public class ExtDirectJsonRequestProcessorThread
    extends SsmJsonRequestProcessorThread
{

  private final SubjectThreadState threadState;

  private final Callable<String> processRequest;

  public ExtDirectJsonRequestProcessorThread() {
    Subject subject = SecurityUtils.getSubject();
    checkState(subject != null, "Subject is not set");
    // create the thread state by this moment as this is created in the master (web container) thread
    threadState = new SubjectThreadState(subject);

    final String baseUrl = BaseUrlHolder.get();
    final String relativePath = BaseUrlHolder.getRelativePath();

    // Use modern lambda syntax and ensure Virtual Thread compatibility with Guice ServletScopes
    processRequest = ServletScopes.transferRequest(() -> {
      threadState.bind();
      UserIdMdcHelper.set();
      try {
        // apply base-url from the original thread
        BaseUrlHolder.set(baseUrl, relativePath);

        return ExtDirectJsonRequestProcessorThread.super.processRequest();
      }
      finally {
        UserIdMdcHelper.unset();
        threadState.restore();
      }
    });
  }

  @Override
  public String processRequest() {
    try {
      // Execute the request using a Virtual Thread
      return Thread.startVirtualThread(() -> processRequest.call()).join();
    }
    // Use pattern matching for exception handling
    catch (Exception e) {
      if (e instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new RuntimeException(e);
    }
  }
}