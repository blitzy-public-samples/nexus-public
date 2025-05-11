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
package com.amazonaws.services.s3.model;

/**
 * Represents a tag for an object stored in S3.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class Tag
{
  private String key;
  private String value;

  /**
   * Constructs a new Tag with the specified key and value.
   *
   * @param key the tag key
   * @param value the tag value
   */
  public Tag(final String key, final String value) {
    this.key = key;
    this.value = value;
  }

  /**
   * Gets the tag key.
   *
   * @return the tag key
   */
  public String getKey() {
    return key;
  }

  /**
   * Sets the tag key.
   *
   * @param key the tag key
   */
  public void setKey(final String key) {
    this.key = key;
  }

  /**
   * Gets the tag value.
   *
   * @return the tag value
   */
  public String getValue() {
    return value;
  }

  /**
   * Sets the tag value.
   *
   * @param value the tag value
   */
  public void setValue(final String value) {
    this.value = value;
  }
}