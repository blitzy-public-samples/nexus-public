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
package org.sonatype.nexus.commands.internal;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;

import org.apache.karaf.shell.commands.info.InfoProvider;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.Mediator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Manages registration of Karaf {@link InfoProvider} instances.
 *
 * @since 3.0
 */
@Named
public class InfoProviderMediator
    extends ComponentSupport
    implements Mediator<Named, InfoProvider, BundleContext>
{
  // Track service registrations to enable proper cleanup in remove()
  private final Map<BeanEntry<Named, InfoProvider>, ServiceRegistration<?>> registrations = new HashMap<>();

  @Override
  public void add(final BeanEntry<Named, InfoProvider> beanEntry, final BundleContext bundleContext) throws Exception {
    log.debug(STR."Adding InfoProvider: \{beanEntry}");
    
    // Create service properties to ensure InfoProviders are properly discovered
    Dictionary<String, Object> properties = new Hashtable<>();
    properties.put("name", beanEntry.getKey().value());
    properties.put("osgi.command.scope", "info");
    
    // Register the service with properties and store the registration
    ServiceRegistration<?> registration = 
        bundleContext.registerService(InfoProvider.class.getName(), beanEntry.getValue(), properties);
    
    // Store the registration for later cleanup
    registrations.put(beanEntry, registration);
  }

  @Override
  public void remove(
      final BeanEntry<Named, InfoProvider> beanEntry,
      final BundleContext bundleContext) throws Exception
  {
    log.debug(STR."Removing InfoProvider: \{beanEntry}");
    
    // Get the service registration for this bean entry
    ServiceRegistration<?> registration = registrations.remove(beanEntry);
    
    // Unregister the service if it exists
    if (registration != null) {
      try {
        registration.unregister();
        log.debug(STR."Successfully unregistered InfoProvider: \{beanEntry}");
      }
      catch (IllegalStateException e) {
        // This can happen if the service was already unregistered
        log.debug(STR."InfoProvider already unregistered: \{beanEntry}");
      }
    }
  }
}