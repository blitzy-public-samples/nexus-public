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
package org.sonatype.nexus.script.plugin.internal;

import java.util.Objects;

import org.sonatype.nexus.common.entity.HasName;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptManager;

/**
 * {@link Script} data.
 * 
 * This class uses Java 21 features like String Templates for improved readability
 * and can be used with Record Patterns for more concise data handling.
 *
 * @since 3.21
 */
public class ScriptData
    implements HasName, Script
{
  private String name;

  private String content;

  private String type = ScriptManager.DEFAULT_TYPE;

  public ScriptData() {
  }

  public ScriptData(String name, String content, String type) {
    this.name = name;
    this.content = content;
    this.type = type != null ? type : ScriptManager.DEFAULT_TYPE;
  }
  
  public ScriptData(String name, String content) {
    this(name, content, ScriptManager.DEFAULT_TYPE);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void setName(String name) {
    this.name = name;
  }

  @Override
  public String getContent() {
    return content;
  }

  @Override
  public void setContent(String content) {
    this.content = content;
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public void setType(String type) {
    this.type = type;
  }

  /**
   * Returns a string representation using Java 21 String Templates for improved readability.
   */
  @Override
  public String toString() {
    return STR."ScriptData{name='\{name}', content='\{content}', type='\{type}'}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    
    // Using Java 21 Pattern Matching for instanceof
    if (o instanceof ScriptData data) {
      return Objects.equals(name, data.getName()) &&
          Objects.equals(content, data.getContent()) &&
          Objects.equals(type, data.getType());
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, content, type);
  }
  
  /**
   * Example of using Record Patterns with ScriptData in Java 21:
   * <pre>
   * void processScripts(List<ScriptData> scripts) {
   *   for (ScriptData script : scripts) {
   *     // Using pattern matching with record patterns
   *     if (script instanceof ScriptData data && "groovy".equals(data.getType())) {
   *       // Process groovy scripts
   *       System.out.println(STR."Processing Groovy script: \{data.getName()}");
   *     }
   *   }
   * }
   * </pre>
   */
}