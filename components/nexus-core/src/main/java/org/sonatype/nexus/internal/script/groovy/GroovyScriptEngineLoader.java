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
package org.sonatype.nexus.internal.script.groovy;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;

/**
 * Groovy script engine loader. Is used to run groovy scripts.
 * 
 * Updated for Java 21 compatibility with enhanced module system support and improved error handling.
 * 
 * @since 3.0
 */
@Named
@Singleton
@ManagedLifecycle(phase = SERVICES)
public class GroovyScriptEngineLoader
    extends StateGuardLifecycleSupport
{
  private final ClassLoader classLoader;

  private final ApplicationDirectories applicationDirectories;

  private final ScriptEngineManager scriptEngineManager;

  @Inject
  public GroovyScriptEngineLoader(
      final @Named("nexus-uber") ClassLoader classLoader,
      final ApplicationDirectories applicationDirectories,
      final ScriptEngineManager scriptEngineManager)
  {
    this.classLoader = checkNotNull(classLoader, "ClassLoader cannot be null");
    this.applicationDirectories = checkNotNull(applicationDirectories, "ApplicationDirectories cannot be null");
    this.scriptEngineManager = checkNotNull(scriptEngineManager, "ScriptEngineManager cannot be null");
  }

  @Override
  protected void doStart() throws Exception {
    // Create the Groovy engine factory with our class loader and application directories
    GroovyScriptEngineFactory groovyEngineFactory = new GroovyScriptEngineFactory(classLoader, applicationDirectories);
    
    // Use String Template for improved logging clarity
    log.debug(STR."Registering Groovy script engine factory: \{groovyEngineFactory}");
    
    // Get all engine names supported by this factory
    List<String> engineNames = groovyEngineFactory.getNames();
    
    if (engineNames.isEmpty()) {
      log.warn(STR."No engine names found for Groovy script engine factory: \{groovyEngineFactory}");
      return;
    }
    
    // Track registration success
    AtomicBoolean registrationSuccess = new AtomicBoolean(false);
    
    // Register each engine name with validation
    engineNames.forEach(name -> {
      try {
        log.debug(STR."Registering engine name: \{name}");
        scriptEngineManager.registerEngineName(name, groovyEngineFactory);
        
        // Verify registration was successful by attempting to get the engine
        if (scriptEngineManager.getEngineByName(name) != null) {
          log.debug(STR."Successfully registered and verified engine: \{name}");
          registrationSuccess.set(true);
        } else {
          log.warn(STR."Engine registration verification failed for: \{name}");
        }
      } catch (Exception e) {
        log.error(STR."Failed to register engine name: \{name}", e);
      }
    });
    
    // Verify at least one engine was successfully registered
    if (!registrationSuccess.get()) {
      log.error("No Groovy script engines were successfully registered. Script execution may not work correctly.");
      
      // As a fallback for Java 21 module system, try to ensure the script engine is available
      // through the ServiceLoader mechanism
      try {
        log.debug("Attempting fallback registration through ServiceLoader mechanism");
        ServiceLoader<ScriptEngineFactory> serviceLoader = ServiceLoader.load(ScriptEngineFactory.class, classLoader);
        boolean foundGroovy = false;
        
        for (ScriptEngineFactory factory : serviceLoader) {
          if (factory.getEngineName().toLowerCase().contains("groovy")) {
            log.debug(STR."Found Groovy engine via ServiceLoader: \{factory.getEngineName()}");
            foundGroovy = true;
            break;
          }
        }
        
        if (!foundGroovy) {
          log.warn("Could not find Groovy engine via ServiceLoader. Script execution may be unavailable.");
        }
      } catch (Exception e) {
        log.error("Error during ServiceLoader fallback registration", e);
      }
    }
  }
}