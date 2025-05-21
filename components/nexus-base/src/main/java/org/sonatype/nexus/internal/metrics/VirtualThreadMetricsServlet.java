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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.nexus.common.app.ApplicationVersion;

import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * Servlet that exposes metrics about Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@Named
@Singleton
public class VirtualThreadMetricsServlet
    extends HttpServlet
{
  private static final long serialVersionUID = 1L;

  private final ApplicationVersion applicationVersion;

  private final ObjectWriter writer;

  @Inject
  public VirtualThreadMetricsServlet(final ApplicationVersion applicationVersion) {
    this.applicationVersion = applicationVersion;
    
    ObjectMapper mapper = new ObjectMapper().registerModule(new MetricsModule(applicationVersion.getVersion()));
    this.writer = mapper.writerWithDefaultPrettyPrinter();
  }

  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException
  {
    resp.setContentType("application/json");
    resp.setHeader("Cache-Control", "must-revalidate,no-cache,no-store");
    resp.setStatus(HttpServletResponse.SC_OK);

    try (PrintWriter out = resp.getWriter()) {
      VirtualThreadMetrics metrics = collectVirtualThreadMetrics();
      writer.writeValue(out, metrics);
    }
  }

  /**
   * Collects metrics about Java 21 Virtual Threads.
   * 
   * @return VirtualThreadMetrics containing platform and virtual thread statistics
   */
  private VirtualThreadMetrics collectVirtualThreadMetrics() {
    VirtualThreadMetrics metrics = new VirtualThreadMetrics();
    
    // Get JVM thread metrics (platform threads)
    metrics.setTotalStartedThreadCount(ManagementFactory.getThreadMXBean().getTotalStartedThreadCount());
    metrics.setThreadCount(ManagementFactory.getThreadMXBean().getThreadCount());
    metrics.setPeakThreadCount(ManagementFactory.getThreadMXBean().getPeakThreadCount());
    metrics.setDaemonThreadCount(ManagementFactory.getThreadMXBean().getDaemonThreadCount());
    
    // Check if virtual threads are supported
    boolean virtualThreadsSupported = isVirtualThreadsSupported();
    metrics.setVirtualThreadsSupported(virtualThreadsSupported);
    
    // If virtual threads are supported, try to get additional metrics
    if (virtualThreadsSupported) {
      try {
        // Use Executors.newVirtualThreadPerTaskExecutor() to test virtual thread creation
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
          // Successfully created virtual thread executor
          metrics.setVirtualThreadExecutorAvailable(true);
        }
      }
      catch (Exception e) {
        metrics.setVirtualThreadExecutorAvailable(false);
      }
    }
    
    return metrics;
  }

  /**
   * Checks if Virtual Threads are supported in the current JVM.
   */
  private boolean isVirtualThreadsSupported() {
    try {
      // Try to create a virtual thread to check if supported
      Thread virtualThread = Thread.ofVirtual().name("virtual-thread-check").start(() -> {});
      virtualThread.join();
      return true;
    }
    catch (Exception e) {
      return false;
    }
  }

  /**
   * Data class to hold Virtual Thread metrics.
   */
  /**
   * Data class to hold Virtual Thread metrics.
   * 
   * @since 3.60
   */
  public static class VirtualThreadMetrics {
    private long totalStartedThreadCount;
    private int threadCount;
    private int peakThreadCount;
    private int daemonThreadCount;
    private boolean virtualThreadsSupported;
    private boolean virtualThreadExecutorAvailable;
    
    public long getTotalStartedThreadCount() {
      return totalStartedThreadCount;
    }
    
    public void setTotalStartedThreadCount(long totalStartedThreadCount) {
      this.totalStartedThreadCount = totalStartedThreadCount;
    }
    
    public int getThreadCount() {
      return threadCount;
    }
    
    public void setThreadCount(int threadCount) {
      this.threadCount = threadCount;
    }
    
    public int getPeakThreadCount() {
      return peakThreadCount;
    }
    
    public void setPeakThreadCount(int peakThreadCount) {
      this.peakThreadCount = peakThreadCount;
    }
    
    public int getDaemonThreadCount() {
      return daemonThreadCount;
    }
    
    public void setDaemonThreadCount(int daemonThreadCount) {
      this.daemonThreadCount = daemonThreadCount;
    }
    
    public boolean isVirtualThreadsSupported() {
      return virtualThreadsSupported;
    }
    
    public void setVirtualThreadsSupported(boolean virtualThreadsSupported) {
      this.virtualThreadsSupported = virtualThreadsSupported;
    }
    
    public boolean isVirtualThreadExecutorAvailable() {
      return virtualThreadExecutorAvailable;
    }
    
    public void setVirtualThreadExecutorAvailable(boolean virtualThreadExecutorAvailable) {
      this.virtualThreadExecutorAvailable = virtualThreadExecutorAvailable;
    }
  }
}