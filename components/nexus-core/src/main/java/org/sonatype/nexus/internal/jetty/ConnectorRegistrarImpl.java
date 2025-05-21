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
package org.sonatype.nexus.internal.jetty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.bootstrap.jetty.ConnectorConfiguration;
import org.sonatype.nexus.bootstrap.jetty.ConnectorRegistrar;
import org.sonatype.nexus.bootstrap.jetty.JettyServerConfiguration;
import org.sonatype.nexus.bootstrap.jetty.UnsupportedHttpSchemeException;

// Updated import for Jetty 12.0.5 compatibility
import org.eclipse.jetty.http.HttpScheme;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Connector registrar component.
 *
 * @since 3.0
 */
@Singleton
@Named
public class ConnectorRegistrarImpl
    extends ComponentSupport
    implements ConnectorRegistrar
{
  private final JettyServerConfiguration serverConfiguration;

  private final ConcurrentHashMap<ConnectorConfiguration, ServiceRegistration<ConnectorConfiguration>> managedConfigurations;
  
  private final ExecutorService executorService;

  @Inject
  public ConnectorRegistrarImpl(final JettyServerConfiguration serverConfiguration) {
    this.serverConfiguration = checkNotNull(serverConfiguration);
    this.managedConfigurations = new ConcurrentHashMap<>();
    this.executorService = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Shutdown the executor service when the component is stopped.
   */
  @Override
  protected void doStop() throws Exception {
    executorService.shutdown();
    super.doStop();
  }

  @Override
  public List<HttpScheme> availableSchemes() {
    final List<HttpScheme> result = new ArrayList<>();
    for (ConnectorConfiguration defaultConnector : serverConfiguration.defaultConnectors()) {
      result.add(defaultConnector.getScheme());
    }
    return result;
  }

  @Override
  public List<Integer> unavailablePorts() {
    final List<Integer> result = new ArrayList<>();
    for (ConnectorConfiguration defaultConnector : serverConfiguration.defaultConnectors()) {
      result.add(defaultConnector.getPort());
    }
    for (ConnectorConfiguration defaultConnector : managedConfigurations.keySet()) {
      result.add(defaultConnector.getPort());
    }
    return result;
  }

  @Override
  public void addConnector(final ConnectorConfiguration connectorConfiguration) {
    checkNotNull(connectorConfiguration);
    validate(connectorConfiguration);

    executorService.submit(() -> {
      final Bundle bundle = FrameworkUtil.getBundle(connectorConfiguration.getClass());
      if (bundle == null) {
        log.warn(STR."No bundle found for \{connectorConfiguration}, not registering connector");
        return;
      }
      final BundleContext bundleContext = bundle.getBundleContext();
      if (bundleContext == null) {
        log.warn(STR."No context found for bundle \{bundle}, not registering connector");
        return;
      }

      log.info(STR."Adding connector configuration \{connectorConfiguration}");
      final ServiceRegistration<ConnectorConfiguration> serviceRegistration =
          bundleContext.registerService(ConnectorConfiguration.class, connectorConfiguration, null);
      managedConfigurations.put(connectorConfiguration, serviceRegistration);
    });
  }

  @Override
  public void removeConnector(final ConnectorConfiguration connectorConfiguration) {
    checkNotNull(connectorConfiguration);
    
    executorService.submit(() -> {
      final ServiceRegistration<ConnectorConfiguration> serviceRegistration =
          managedConfigurations.remove(connectorConfiguration);
      if (serviceRegistration != null) {
        log.info(STR."Removing connector configuration \{connectorConfiguration}");
        try {
          serviceRegistration.unregister();
        }
        catch (IllegalStateException e) {
          // nop, happens on shutdown when context unregisters automatically all services
          log.debug(STR."Could not unregister connector", e);
        }
      }
    });
  }

  private void validate(final ConnectorConfiguration connectorConfiguration) {
    // Use pattern matching to validate the connector configuration
    switch (connectorConfiguration) {
      case null -> throw new NullPointerException("Connector configuration cannot be null");
      case var config when managedConfigurations.containsKey(config) -> 
          throw new IllegalArgumentException("Connector is already added");
      case var config -> {
        // Validate HTTP scheme
        HttpScheme httpScheme = config.getScheme();
        switch (httpScheme) {
          case null -> throw new NullPointerException("HTTP scheme cannot be null");
          case var scheme when !availableSchemes().contains(scheme) -> 
              throw new UnsupportedHttpSchemeException(scheme);
          default -> { /* Valid scheme */ }
        }
        
        // Validate port
        int port = config.getPort();
        switch (port) {
          case var p when p <= 0 -> 
              throw new IllegalArgumentException(STR."Port must be positive, got \{p}");
          case var p when p >= 65536 -> 
              throw new IllegalArgumentException(STR."Port must be less than 65536, got \{p}");
          case var p when unavailablePorts().contains(p) -> 
              throw new IllegalArgumentException(STR."Port \{p} is already in use");
          default -> { /* Valid port */ }
        }
      }
    }
  }
}