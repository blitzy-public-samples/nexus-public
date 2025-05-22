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
package org.sonatype.nexus.siesta.internal.resteasy;

import java.io.IOException;
import java.util.concurrent.Executors;

import jakarta.annotation.Nullable;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.RuntimeDelegate;

import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.siesta.ComponentContainer;
import org.sonatype.nexus.siesta.SiestaResourceMethodFinder;

import org.eclipse.sisu.BeanEntry;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * RESTEasy {@link ComponentContainer}.
 *
 * @since 3.0
 */
public class ComponentContainerImpl
  extends HttpServletDispatcher
  implements ComponentContainer
{
  private static final Logger log = LoggerFactory.getLogger(ComponentContainerImpl.class);

  private transient final ResteasyDeployment deployment = new SisuResteasyDeployment();

  public ComponentContainerImpl() {
    // Register RESTEasy with JAX-RS as early as possible
    RuntimeDelegate.setInstance(checkNotNull(deployment.getProviderFactory()));
  }

  @Override
  public void init(final ServletConfig servletConfig) throws ServletException {
    final ClassLoader cl = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(ResteasyProviderFactory.class.getClassLoader());
      doInit(servletConfig);
    }
    finally {
      Thread.currentThread().setContextClassLoader(cl);
    }
  }

  private void doInit(final ServletConfig servletConfig) throws ServletException {
    deployment.start();

    servletConfig.getServletContext().setAttribute(ResteasyDeployment.class.getName(), deployment);
    servletConfig.getServletContext().setAttribute(
        SiestaResourceMethodFinder.class.getName(), new SiestaResourceMethodFinder(this, deployment));

    super.init(servletConfig);

    ResteasyProviderFactory providerFactory = getDispatcher().getProviderFactory();
    providerFactory.getContainerResponseFilterRegistry().registerClass(NotCacheableResponseFilter.class);

    if (log.isDebugEnabled()) {
      log.debug(STR."Provider factory: \{providerFactory}");
      log.debug(STR."Configuration: \{providerFactory.getConfiguration()}");
      log.debug(STR."Runtime type: \{providerFactory.getRuntimeType()}");
      log.debug(STR."Built-ins registered: \{providerFactory.isBuiltinsRegistered()}");
      log.debug(STR."Properties: \{providerFactory.getProperties()}");
      log.debug(STR."Dynamic features: \{providerFactory.getServerDynamicFeatures()}");
      log.debug(STR."Enabled features: \{providerFactory.getEnabledFeatures()}");
      log.debug(STR."Class contracts: \{providerFactory.getClassContracts()}");
      log.debug(STR."Reader interceptor registry: \{providerFactory.getServerReaderInterceptorRegistry()}");
      log.debug(STR."Writer interceptor registry: \{providerFactory.getServerWriterInterceptorRegistry()}");
      log.debug(STR."Injector factory: \{providerFactory.getInjectorFactory()}");
      log.debug(STR."Instances: \{providerFactory.getInstances()}");
      log.debug(STR."Exception mappers: \{providerFactory.getExceptionMappers()}");
    }
  }

  @Override
  public void destroy() {
    super.destroy();

    deployment.stop();
  }

  /**
   * Promotes {@link HttpServletDispatcher#service(HttpServletRequest, HttpServletResponse)} to public access.
   * Uses Virtual Threads for improved concurrency and performance.
   */
  @Override
  public void service(final HttpServletRequest request, final HttpServletResponse response)
      throws ServletException, IOException
  {
    // Use Virtual Threads for request processing
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      executor.submit(() -> {
        try {
          // Propagate context to virtual thread
          final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
          Thread.currentThread().setContextClassLoader(contextClassLoader);
          
          // Process the request in the virtual thread
          super.service(request, response);
        } catch (ServletException | IOException e) {
          log.error(STR."Error processing request in virtual thread: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
        return null;
      }).get(); // Wait for completion to ensure response is fully processed
    } catch (Exception e) {
      log.error(STR."Failed to process request with virtual thread: \{e.getMessage()}", e);
      throw new ServletException("Virtual thread request processing failed", e);
    } finally {
      executor.close();
    }
  }

  private static boolean isResource(final Class<?> type) {
    return Resource.class.isAssignableFrom(type);
  }

  @Nullable
  private static String resourcePath(final Class<?> type) {
    Path path = type.getAnnotation(Path.class);
    if (path != null) {
      return path.value();
    }
    return null;
  }

  @Override
  public void addComponent(final BeanEntry<?, ?> entry) throws Exception {
    Class<?> type = entry.getImplementationClass();
    if (isResource(type)) {
      getDispatcher().getRegistry().addResourceFactory(new SisuResourceFactory(entry));
      String path = resourcePath(type);
      if (path == null) {
        log.warn(STR."Found resource implementation missing @Path: \{type.getName()}");
      }
      else {
        log.debug(STR."Added resource: \{type.getName()} with path: \{path}");
      }
    }
    else {
      // TODO: Doesn't seem to be a late-biding/factory here so we create the object early
      getDispatcher().getProviderFactory().register(entry.getValue());
      log.debug(STR."Added component: \{type.getName()}");
    }
  }

  @Override
  public void removeComponent(final BeanEntry<?, ?> entry) throws Exception {
    Class<?> type = entry.getImplementationClass();
    if (isResource(type)) {
      getDispatcher().getRegistry().removeRegistrations(type);
      String path = resourcePath(type);
      log.debug(STR."Removed resource: \{type.getName()} with path: \{path}");
    }
    else {
      ResteasyProviderFactory providerFactory = getDispatcher().getProviderFactory();
      if (providerFactory instanceof SisuResteasyProviderFactory) {
        ((SisuResteasyProviderFactory) providerFactory).removeRegistrations(type);
        log.debug(STR."Removed component: \{type.getName()}");
      }
      else {
        log.warn(STR."Component removal not supported; Unable to remove component: \{type.getName()}");
      }
    }
  }
}