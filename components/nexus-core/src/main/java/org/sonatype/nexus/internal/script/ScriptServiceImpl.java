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

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.GlobalComponentLookupHelper;
import org.sonatype.nexus.common.script.ScriptApi;
import org.sonatype.nexus.common.script.ScriptCleanupHandler;
import org.sonatype.nexus.common.script.ScriptService;

import org.eclipse.sisu.inject.BeanLocator;

import static java.lang.StringTemplate.STR;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Default {@link ScriptService} implementation.
 *
 * @since 3.0
 */
@Named
@Singleton
public class ScriptServiceImpl
    extends ComponentSupport
    implements ScriptService
{
  public static final String SCRIPT_CLEANUP_HANDLER = "scriptCleanupHandler";

  private final ScriptEngineManager engineManager;

  private final BeanLocator beanLocator;

  private final GlobalComponentLookupHelper lookupHelper;

  private final List<ScriptApi> scriptApis;

  private final ScriptCleanupHandler scriptCleanupHandler;

  private final boolean allowOnlyGroovy;

  @Inject
  public ScriptServiceImpl(
      final ScriptEngineManager engineManager,
      final BeanLocator beanLocator,
      final GlobalComponentLookupHelper lookupHelper,
      final List<ScriptApi> scriptApis,
      final ScriptCleanupHandler scriptCleanupHandler,
      @Named("${nexus.scripts.groovyOnly:-true}") final boolean allowOnlyGroovy)
  {
    this.engineManager = checkNotNull(engineManager);
    this.beanLocator = checkNotNull(beanLocator);
    this.lookupHelper = checkNotNull(lookupHelper);
    this.scriptApis = checkNotNull(scriptApis);
    this.scriptCleanupHandler = checkNotNull(scriptCleanupHandler);
    this.allowOnlyGroovy = allowOnlyGroovy;
  }

  @Override
  @Nonnull
  public ScriptEngine engineForLanguage(final String language) {
    validateLanguage(language);

    log.trace(STR."Resolving engine for language: \{language}");
    ScriptEngine engine = engineManager.getEngineByName(language);
    checkState(engine != null, STR."Missing engine for language: \{language}");
    log.trace(STR."Engine: \{engine}");
    return engine;
  }

  @Override
  public void applyDefaultBindings(final Bindings bindings) {
    checkNotNull(bindings);

    // TODO: Could potentially expose these in GLOBAL_SCOPE bindings?
    // TODO: Consider options to allow scripts more easily become injected aware?

    bindings.put("beanLocator", beanLocator);
    bindings.put("container", lookupHelper);
    bindings.put(SCRIPT_CLEANUP_HANDLER, scriptCleanupHandler);
    for (ScriptApi scriptApi : scriptApis) {
      bindings.put(scriptApi.getName(), scriptApi);
    }
  }

  @Nonnull
  @Override
  public ScriptContext createContext(final String language) {
    ScriptContext context = new SimpleScriptContext();
    context.setBindings(engineForLanguage(language).createBindings(), ScriptContext.ENGINE_SCOPE);
    return context;
  }

  @Override
  public void customizeBindings(
      final ScriptContext context,
      final int scope,
      final Map<String, Object> customizations)
  {
    Bindings bindings = context.getBindings(scope);
    applyDefaultBindings(bindings);
    customizations.forEach(bindings::put);
  }

  @Override
  public void customizeBindings(final ScriptContext context, final Map<String, Object> customizations) {
    customizeBindings(context, ScriptContext.ENGINE_SCOPE, customizations);
  }

  @Override
  public Object eval(final String language, final String script, final ScriptContext context) throws ScriptException {
    checkNotNull(language);
    checkNotNull(script);
    checkNotNull(context);
    
    // Use virtual threads for script evaluation to improve performance
    try {
      Future<Object> result = Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
          engineForLanguage(language).eval(script, context));
      return result.get();
    } 
    catch (Exception e) {
      if (e.getCause() instanceof ScriptException) {
        throw (ScriptException) e.getCause();
      }
      throw new ScriptException(STR."Error executing script: \{e.getMessage()}");
    }
  }

  @Override
  public Object eval(final String language, final String script, final Map<String, Object> customBindings)
      throws ScriptException
  {
    ScriptContext context = createContext(language);
    customizeBindings(context, customBindings);
    return eval(language, script, context);
  }

  private void validateLanguage(final String language) {
    checkNotNull(language);

    // Use pattern matching for switch to improve readability and maintainability
    switch (language) {
      case ScriptEngineManagerProvider.DEFAULT_LANGUAGE -> {
        // Groovy is always allowed
      }
      case String s when !allowOnlyGroovy -> {
        // Other languages are allowed when allowOnlyGroovy is false
      }
      default -> {
        throw new IllegalScriptLanguageException(STR."Language: \{language} is not allowed");
      }
    }
  }
}
