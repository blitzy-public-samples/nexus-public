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

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

import org.codehaus.groovy.syntax.Types;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.script.ScriptCleanupHandler;
import org.sonatype.nexus.internal.script.ScriptTask;

import com.google.common.annotations.VisibleForTesting;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.sonatype.nexus.internal.script.ScriptServiceImpl.SCRIPT_CLEANUP_HANDLER;

/**
 * Groovy {@link ScriptEngineFactory} with Java 21 enhancements.
 *
 * @since 3.0
 */
@Named("groovy")
@Singleton
public class GroovyScriptEngineFactory
    extends org.codehaus.groovy.jsr223.GroovyScriptEngineFactory
{
  private static final Logger log = LoggerFactory.getLogger(GroovyScriptEngineFactory.class);
  
  // Virtual thread executor for script execution
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = 
      Executors.newVirtualThreadPerTaskExecutor();

  private final ClassLoader classLoader;

  private final ApplicationDirectories applicationDirectories;

  private GroovyScriptEngine engine;

  @Inject
  public GroovyScriptEngineFactory(
      @Named("nexus-uber") final ClassLoader classLoader,
      final ApplicationDirectories applicationDirectories)
  {
    this.classLoader = checkNotNull(classLoader);
    this.applicationDirectories = checkNotNull(applicationDirectories);
  }

  private GroovyScriptEngine create() {
    // custom the configuration of the compiler
    CompilerConfiguration cc = new CompilerConfiguration();
    cc.setTargetDirectory(new File(applicationDirectories.getTemporaryDirectory(), "groovy-classes"));
    cc.setSourceEncoding("UTF-8");
    cc.setScriptBaseClass(ScriptWithCleanup.class.getName());
    cc.addCompilationCustomizers(secureASTCustomizer());
    GroovyClassLoader gcl = new GroovyClassLoader(classLoader, cc);

    engine = new GroovyScriptEngine(gcl);
    
    // Use String Templates for improved logging clarity
    log.info(STR."Created engine: \{engine}");

    return engine;
  }

  /**
   * Secure potentially dangerous calls in scripts with Java 21's enhanced security model.
   */
  private CompilationCustomizer secureASTCustomizer() {
    SecureASTCustomizer secureASTCustomizer = new SecureASTCustomizer();
    
    // Blacklist System class to prevent direct system access
    secureASTCustomizer.setImportsBlacklist(Collections.singletonList("java.lang.System"));
    secureASTCustomizer.setReceiversBlackList(Collections.singletonList(System.class.getName()));
    
    // Prevent access to Java 21 specific classes that might bypass security
    List<String> additionalBlacklist = List.of(
        "java.lang.ProcessHandle",
        "java.lang.Runtime",
        "java.util.concurrent.StructuredTaskScope");

    List<Integer> disallowedTokens = List.of(
            Types.KEYWORD_SYNCHRONIZED,
            Types.KEYWORD_THROW,
            Types.KEYWORD_THIS, // optional
            Types.KEYWORD_SUPER // optional
    );
    secureASTCustomizer.setIndirectImportCheckEnabled(true);
    secureASTCustomizer.setDisallowedTokens(disallowedTokens); // Avoid pinning virtual threads
    
    return secureASTCustomizer;
  }

  // TODO: Groovy engine is thread-safe, so re-use the engine instance instead of creating new ones each time asked
  // TODO: sort out if there are any issues with engine scope bindings, or other wrinkles involved with shared engine

  // FIXME: Cope with the script-source -> class cache that the default impl has?
  // FIXME: ... in addition to the class cache which the GCL has

  @Override
  public synchronized ScriptEngine getScriptEngine() {
    if (engine == null) {
      engine = create();
    }
    return engine;
  }

  /**
   * Executes a script using Virtual Threads for better performance under high concurrency.
   * 
   * @param script The script to execute
   * @param binding The binding context for the script
   * @return The result of script execution
   */
  public Object executeWithVirtualThread(Script script, Binding binding) {
    return VIRTUAL_THREAD_EXECUTOR.submit(() -> {
      script.setBinding(binding);
      return script.run();
    });
  }

  @VisibleForTesting
  static String getContext(final Binding binding) {
    Optional<String> taskContext = getVariable(binding, "task", ScriptTask.class)
        .map(ts -> STR."Task '\{ts.getName()}'");
    Optional<String> scriptContext = getVariable(binding, "scriptName", String.class)
        .map(name -> STR."Script '\{name}'");
    return Stream.of(taskContext, scriptContext)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .findFirst()
        .orElse("An unknown script");
  }

  /**
   * Gets a variable from the binding with Pattern Matching for instanceof checks and casting.
   */
  private static <T> Optional<T> getVariable(final Binding binding, final String name, final Class<T> type) {
    if (binding.hasVariable(name)) {
      Object instance = binding.getVariable(name);
      if (type.isInstance(instance)) {
        return Optional.of(type.cast(instance));
      }
    }
    return Optional.empty();
  }


  /**
   * Script with cleanup support enhanced for Java 21 with improved resource management.
   */
  public abstract static class ScriptWithCleanup
      extends Script
  {
    @Override
    public Object run() {
      try {
        return scriptBody();
      }
      finally {
        // Use Pattern Matching for instanceof check and casting
        Object scriptCleanupHelper = this.getBinding().getVariable(SCRIPT_CLEANUP_HANDLER);
        if (scriptCleanupHelper instanceof ScriptCleanupHandler cleanupHandler) {
          cleanupHandler.cleanup(getContext(this.getBinding()));
        }
      }
    }

    protected abstract Object scriptBody();
  }
}