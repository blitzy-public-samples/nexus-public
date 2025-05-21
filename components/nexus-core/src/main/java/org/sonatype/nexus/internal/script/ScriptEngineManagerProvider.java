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
package org.sonatype.nexus.internal.script;

import java.util.ArrayList;
import java.util.List;
import java.util.SequencedCollection;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import org.sonatype.goodies.common.ComponentSupport;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Provides the {@link ScriptEngineManager}.
 *
 * @since 3.0
 */
@Named
@Singleton
public class ScriptEngineManagerProvider
    extends ComponentSupport
    implements Provider<ScriptEngineManager>
{
  public static final String DEFAULT_LANGUAGE = "groovy";

  // TODO: Could consider a Mediator, except the ScriptEngineManager provides no means to "unregister"

  private final SequencedCollection<ScriptEngineFactory> factories;

  @Inject
  public ScriptEngineManagerProvider(final List<ScriptEngineFactory> factories) {
    this.factories = checkNotNull(factories);
  }

  @Override
  public ScriptEngineManager get() {
    // limit detection of engines to the runtime's default engines, other engines should register via guice
    ScriptEngineManager engineManager = new ScriptEngineManager(ClassLoader.getSystemClassLoader());

    var available = new ArrayList<ScriptEngineFactory>();
    available.addAll(engineManager.getEngineFactories()); // detected by runtime

    // Register engine-factories detected via injection
    for (ScriptEngineFactory factory : factories) {
      log.debug(STR."Registering engine-factory: {factory}");

      // Register engine names
      var names = factory.getNames();
      if (names != null && !names.isEmpty()) {
        for (String name : names) {
          engineManager.registerEngineName(name, factory);
        }
      } else {
        log.warn(STR."Engine factory {factory} has no names");
      }

      // Register MIME types
      var mimeTypes = factory.getMimeTypes();
      if (mimeTypes != null && !mimeTypes.isEmpty()) {
        for (String mimeType : mimeTypes) {
          engineManager.registerEngineMimeType(mimeType, factory);
        }
      } else {
        log.debug(STR."Engine factory {factory} has no mime types");
      }

      // Register extensions
      var extensions = factory.getExtensions();
      if (extensions != null && !extensions.isEmpty()) {
        for (String ext : extensions) {
          engineManager.registerEngineExtension(ext, factory);
        }
      } else {
        log.debug(STR."Engine factory {factory} has no extensions");
      }

      available.add(factory);
    }

    // Dump some information about detected engine factories
    log.info(STR."Detected {available.size()} engine-factories");

    for (ScriptEngineFactory factory : available) {
      log.info(STR."""
          Engine-factory: {factory.getEngineName()} v{factory.getEngineVersion()}; 
          language={factory.getLanguageName()}, 
          version={factory.getLanguageVersion()}, 
          names={factory.getNames()}, 
          mime-types={factory.getMimeTypes()}, 
          extensions={factory.getExtensions()}
          """);
    }

    log.info(STR."Default language: {DEFAULT_LANGUAGE}");

    return engineManager;
  }
}