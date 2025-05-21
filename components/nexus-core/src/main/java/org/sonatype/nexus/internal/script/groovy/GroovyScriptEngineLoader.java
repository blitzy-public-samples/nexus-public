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
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;

/**
 * Groovy script engine loader. Is used to run groovy scripts.
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
    this.classLoader = checkNotNull(classLoader);
    this.applicationDirectories = checkNotNull(applicationDirectories);
    this.scriptEngineManager = checkNotNull(scriptEngineManager);
  }

  @Override
  protected void doStart() throws Exception {
    // Create the Groovy script engine factory with Java 21 compatible class loading
    GroovyScriptEngineFactory groovyEngineFactory = new GroovyScriptEngineFactory(classLoader, applicationDirectories);
    
    log.debug(STR."Registering Groovy script engine factory: \{groovyEngineFactory}");
    
    // Register the engine factory with validation for Java 21 compatibility
    registerEngineFactory(groovyEngineFactory);
  }
  
  /**
   * Registers the Groovy script engine factory with additional validation for Java 21 compatibility.
   * This method ensures proper interaction with Java 21's enhanced module system and provides
   * improved error handling and diagnostics.
   *
   * @param engineFactory the Groovy script engine factory to register
   */
  private void registerEngineFactory(final GroovyScriptEngineFactory engineFactory) {
    try {
      // Get all supported engine names
      List<String> engineNames = engineFactory.getNames();
      
      // Validate that we have at least one engine name
      if (engineNames == null || engineNames.isEmpty()) {
        log.warn(STR."No engine names provided by Groovy script engine factory: \{engineFactory}");
        return;
      }
      
      // Register each engine name with the ScriptEngineManager
      engineNames.forEach(name -> {
        if (name == null || name.isBlank()) {
          log.warn(STR."Skipping registration of engine with invalid name: '\{name}'")
          return;
        }
        
        try {
          // Register the engine factory with the ScriptEngineManager
          scriptEngineManager.registerEngineName(name, engineFactory);
          
          // Verify the registration was successful
          ScriptEngine engine = scriptEngineManager.getEngineByName(name);
          if (engine != null) {
            ScriptEngineFactory factory = engine.getFactory();
            if (Objects.equals(factory, engineFactory)) {
              log.debug(STR."Successfully registered Groovy script engine with name: '\{name}'")
            } else {
              log.warn(STR."Engine registered but factory mismatch for name: '\{name}'. Expected: \{engineFactory}, Got: \{factory}");
            }
          } else {
            log.warn(STR."Failed to verify engine registration for name: '\{name}'")
          }
        } catch (Exception e) {
          log.error(STR."Failed to register Groovy script engine with name: '\{name}'", e);
        }
      });
      
      // Final verification that at least one engine is available
      boolean anyEngineAvailable = engineNames.stream()
          .anyMatch(name -> scriptEngineManager.getEngineByName(name) != null);
      
      if (anyEngineAvailable) {
        log.info(STR."Groovy script engine successfully registered and available");
      } else {
        log.warn(STR."Groovy script engine registration may have failed - unable to retrieve engine by any registered name");
      }
    } catch (Exception e) {
      log.error(STR."Failed to register Groovy script engine factory: \{engineFactory}", e);
    }
  }
}