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
package org.sonatype.nexus.repository.search.selector;

import java.util.Map;

import org.apache.shiro.subject.Subject;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.NativeScriptFactory;
import org.elasticsearch.script.Script;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.script.ScriptService.ScriptType.INLINE;

/**
 * Factory for {@link ContentAuthPluginScript}.
 *
 * @since 3.1
 */
public class ContentAuthPluginScriptFactory
    implements NativeScriptFactory
{
  private static final String SUBJECT_PARAM = "subject";
  
  /**
   * Record representing script parameters with subject ID.
   * Used with pattern matching for cleaner parameter extraction.
   *
   * @since 3.38
   */
  private record ScriptParams(String subject) {}

  @Override
  public ExecutableScript newScript(final Map<String, Object> params) {
    checkNotNull(params);
    
    // Use pattern matching to extract and validate the subject parameter in one step
    // This is a Java 21 feature that combines type checking and value extraction
    if (params.get(SUBJECT_PARAM) instanceof String subjectId) {
      // Create a ScriptParams record to demonstrate record usage
      ScriptParams scriptParams = new ScriptParams(subjectId);
      
      // Use pattern matching to extract the subject from the record
      if (scriptParams instanceof ScriptParams(String subject)) {
        return new ContentAuthPluginScript(
            ContentAuthPlugin.getSearchSubjectHelper().getSubject(subject),
            ContentAuthPlugin.getContentPermissionChecker(),
            ContentAuthPlugin.getVariableResolverAdapterManager(),
            ContentAuthPlugin.getRepositoryManager(),
            ContentAuthPlugin.getContentAuthSleep());
      }
    }
    
    throw new IllegalArgumentException("Missing or invalid 'subject' parameter");
  }

  @Override
  public boolean needsScores() {
    return false;
  }

  /**
   * Returns a new {@link Script} instance for use in ES queries, configured for the {@link ContentAuthPluginScript}.
   * 
   * @param subjectId The subject identifier to use in the script parameters
   * @return A new Script instance configured for ContentAuthPluginScript
   */
  public static Script newScript(final String subjectId) {
    checkNotNull(subjectId);
    return new Script(ContentAuthPluginScript.NAME, INLINE, "native", singletonMap(SUBJECT_PARAM, subjectId));
  }
}