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
package org.sonatype.nexus.repository.upload.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.sonatype.nexus.repository.view.payloads.TempBlob;

/**
 * A multipart form that stores temporary blobs and form fields.
 * 
 * @since 3.16
 */
public class BlobStoreMultipartForm implements AutoCloseable
{
  private final SequencedMap<String, TempBlobFormField> files = new LinkedHashMap<>();

  private final SequencedMap<String, String> formFields = new LinkedHashMap<>();
  
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /**
   * Get a file by name.
   * 
   * @param fileName the name of the file to retrieve
   * @return the file or null if not found
   */
  public TempBlobFormField getFile(final String fileName) {
    lock.readLock().lock();
    try {
      return files.get(fileName);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all files in the form.
   * 
   * @return a map of file names to files
   */
  public Map<String, TempBlobFormField> getFiles() {
    lock.readLock().lock();
    try {
      return Map.copyOf(files);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get a form field by name.
   * 
   * @param formField the name of the form field to retrieve
   * @return the form field value or null if not found
   */
  public String getFormField(final String formField) {
    lock.readLock().lock();
    try {
      return formFields.get(formField);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all form fields.
   * 
   * @return a map of form field names to values
   */
  public Map<String, String> getFormFields() {
    lock.readLock().lock();
    try {
      return Map.copyOf(formFields);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Add a file to the form.
   * 
   * @param fieldName the field name
   * @param file the file to add
   */
  public void putFile(final String fieldName, final TempBlobFormField file) {
    lock.writeLock().lock();
    try {
      files.put(fieldName, file);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Add a form field to the form.
   * 
   * @param name the field name
   * @param value the field value
   */
  public void putFormField(final String name, final String value) {
    lock.writeLock().lock();
    try {
      formFields.put(name, value);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void close() throws Exception {
    lock.writeLock().lock();
    try {
      Exception firstException = null;
      
      for (TempBlobFormField file : files.values()) {
        try {
          if (file instanceof TempBlobFormField(var fieldName, var fileName, var tempBlob)) {
            tempBlob.close();
          }
        } catch (Exception e) {
          if (firstException == null) {
            firstException = e;
          } else {
            firstException.addSuppressed(e);
          }
        }
      }
      
      files.clear();
      formFields.clear();
      
      if (firstException != null) {
        throw firstException;
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * A record representing a file in a multipart form.
   * 
   * @param fieldName the field name
   * @param fileName the file name
   * @param tempBlob the temporary blob
   */
  public record TempBlobFormField(String fieldName, String fileName, TempBlob tempBlob) {
  }
}