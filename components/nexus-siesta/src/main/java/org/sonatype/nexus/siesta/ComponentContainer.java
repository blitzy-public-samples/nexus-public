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
package org.sonatype.nexus.siesta;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.nexus.rest.Component;
import org.sonatype.nexus.rest.Resource;

import org.eclipse.sisu.BeanEntry;
import org.jboss.resteasy.core.Dispatcher;

/**
 * Siesta {@link Component} (and {@link Resource} container abstraction.
 * <p>
 * Compatible with Java 21 and RESTEasy 6.2.7.Final, with support for Virtual Thread
 * context propagation to ensure thread-local variables and other context information
 * are properly maintained when using virtual threads for request handling.
 *
 * @since 3.0
 */
public interface ComponentContainer
{
  /**
   * Initialize the component container.
   */
  void init(final ServletConfig config) throws ServletException;

  /**
   * Service an HTTP request.
   * <p>
   * When running on Java 21 with Virtual Threads, this method ensures proper context
   * propagation between the calling thread and any virtual threads used for request handling.
   */
  void service(final HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException;

  /**
   * Destroy the component container.
   */
  void destroy();

  /**
   * Add a component to the container.
   */
  void addComponent(BeanEntry<?,?> entry) throws Exception;

  /**
   * Remove a component from the container.
   */
  void removeComponent(BeanEntry<?,?> entry) throws Exception;

  /**
   * Get the RESTEasy dispatcher.
   */
  Dispatcher getDispatcher();
}