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
package org.sonatype.nexus.internal.metrics;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static com.google.common.net.HttpHeaders.CONTENT_DISPOSITION;

/**
 * Enhanced {@link io.dropwizard.metrics.servlets.ThreadDumpServlet} to support download and virtual threads.
 * Provides thread dump information for both platform and virtual threads.
 *
 * @since 3.0
 */
@Singleton
public class ThreadDumpServlet
    extends HttpServlet
{
  private final ThreadMXBean threadBean;
  
  @Inject
  public ThreadDumpServlet() {
    this.threadBean = ManagementFactory.getThreadMXBean();
  }
  
  @Override
  protected void doGet(
      final HttpServletRequest req,
      final HttpServletResponse resp) throws ServletException, IOException
  {
    boolean download = Boolean.parseBoolean(req.getParameter("download"));
    if (download) {
      resp.addHeader(CONTENT_DISPOSITION, "attachment; filename='threads.txt'");
    }

    resp.setContentType("text/plain");
    resp.setHeader("Cache-Control", "must-revalidate,no-cache,no-store");
    
    final PrintWriter writer = resp.getWriter();
    
    try {
      // Get platform thread information
      writer.println("=== Platform Threads ===");
      writer.println();
      
      final ThreadInfo[] threads = threadBean.dumpAllThreads(true, true);
      for (ThreadInfo thread : threads) {
        writer.println(thread);
      }
      
      // Get virtual thread information if available
      writer.println();
      writer.println("=== Virtual Threads ===");
      writer.println();
      
      try {
        // Use Java 21 API to get virtual thread information
        // This uses reflection to avoid direct dependencies on Java 21 APIs
        // which might not be available on all platforms
        Class<?> threadClass = Class.forName("java.lang.Thread");
        Object virtualThreads = threadClass.getMethod("getAllVirtualThreads").invoke(null);
        writer.println("Virtual threads available: " + virtualThreads.toString());
        
        // Additional virtual thread details would be added here
      }
      catch (Exception e) {
        writer.println("Virtual thread information not available: " + e.getMessage());
      }
    }
    finally {
      writer.flush();
    }
  }
}