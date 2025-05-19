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
package org.sonatype.nexus.common.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for ZIP file operations using Java 21 Virtual Threads for improved performance.
 * Provides methods for zipping and unzipping files with parallel processing capabilities.
 */
public class ZipSupport
{
  private static final Logger log = LoggerFactory.getLogger(ZipSupport.class);
  
  // Default buffer size increased to 8KB for better performance
  private static final int DEFAULT_BUFFER_SIZE = 8192;

  /**
   * Zips files from the specified path into a ZIP file.
   * Maintains backward compatibility with the original method.
   *
   * @param path The base path where the files are located
   * @param filesToZip List of file names to be zipped
   * @param zipFileName The name of the output ZIP file
   * @throws IOException If an I/O error occurs
   */
  public static void zipFiles(Path path, List<String> filesToZip, String zipFileName) throws IOException {
    zipFiles(path, filesToZip, zipFileName, true);
  }

  /**
   * Zips files from the specified path into a ZIP file with option to use parallel processing.
   *
   * @param path The base path where the files are located
   * @param filesToZip List of file names to be zipped
   * @param zipFileName The name of the output ZIP file
   * @param useParallel Whether to use parallel processing with Virtual Threads
   * @throws IOException If an I/O error occurs
   */
  public static void zipFiles(Path path, List<String> filesToZip, String zipFileName, boolean useParallel) 
      throws IOException {
    if (filesToZip == null || filesToZip.isEmpty()) {
      log.warn("No files to zip");
      return;
    }

    // Create parent directories if they don't exist
    Path zipFilePath = Path.of(zipFileName);
    if (zipFilePath.getParent() != null) {
      Files.createDirectories(zipFilePath.getParent());
    }
    
    try (FileOutputStream fos = new FileOutputStream(zipFileName);
         ZipOutputStream zos = new ZipOutputStream(fos)) {
      
      if (useParallel && filesToZip.size() > 1) {
        // Use Virtual Threads for parallel processing when multiple files
        zipFilesParallel(path, filesToZip, zos);
      }
      else {
        // Use sequential processing for single file or when parallel is disabled
        zipFilesSequential(path, filesToZip, zos);
      }
    }
  }
  
  /**
   * Zips files sequentially using improved buffer handling.
   *
   * @param path The base path where the files are located
   * @param filesToZip List of file names to be zipped
   * @param zos The ZIP output stream
   * @throws IOException If an I/O error occurs
   */
  /**
   * Zips files sequentially using improved buffer handling.
   *
   * @param path The base path where the files are located
   * @param filesToZip List of file names to be zipped
   * @param zos The ZIP output stream
   * @throws IOException If an I/O error occurs
   */
  private static void zipFilesSequential(Path path, List<String> filesToZip, ZipOutputStream zos) 
      throws IOException {
    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
    
    for (String fileName : filesToZip) {
      Path filePath = path.resolve(fileName);
      File file = filePath.toFile();
      
      if (!file.exists()) {
        log.info("The file '{}' does not exist in folder '{}'", fileName, path);
        continue;
      }
      
      // Use try-with-resources to ensure proper resource management
      try (FileInputStream fis = new FileInputStream(file)) {
        zos.putNextEntry(new ZipEntry(fileName));
        
        // Use NIO channels for better performance when file is large enough
        if (file.length() > DEFAULT_BUFFER_SIZE * 10) {
          try (FileChannel channel = fis.getChannel()) {
            ByteBuffer byteBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
            while (channel.read(byteBuffer) > 0) {
              byteBuffer.flip();
              zos.write(byteBuffer.array(), 0, byteBuffer.limit());
              byteBuffer.clear();
            }
          }
        } else {
          // Use regular stream for small files
          int length;
          while ((length = fis.read(buffer)) > 0) {
            zos.write(buffer, 0, length);
          }
        }
        
        zos.closeEntry();
      }
    }
  }
  
  /**
   * Zips files in parallel using Virtual Threads for improved performance.
   * Each file is processed in its own Virtual Thread, allowing for concurrent processing.
   *
   * @param path The base path where the files are located
   * @param filesToZip List of file names to be zipped
   * @param zos The ZIP output stream
   * @throws IOException If an I/O error occurs
   */
  /**
   * Zips files in parallel using Virtual Threads for improved performance.
   * Each file is processed in its own Virtual Thread, allowing for concurrent processing.
   *
   * @param path The base path where the files are located
   * @param filesToZip List of file names to be zipped
   * @param zos The ZIP output stream
   * @throws IOException If an I/O error occurs
   */
  private static void zipFilesParallel(Path path, List<String> filesToZip, ZipOutputStream zos) 
      throws IOException {
    // Synchronize access to the ZipOutputStream since it's not thread-safe
    final Object zipLock = new Object();
    
    // Track exceptions that occur in virtual threads
    final List<Exception> exceptions = new ArrayList<>();
    
    // Use CountDownLatch to wait for all threads to complete
    final CountDownLatch latch = new CountDownLatch(filesToZip.size());
    
    // Create a virtual thread per file using the Executors factory
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String fileName : filesToZip) {
        executor.submit(() -> {
          try {
            Path filePath = path.resolve(fileName);
            File file = filePath.toFile();
            
            if (!file.exists()) {
              log.info("The file '{}' does not exist in folder '{}'", fileName, path);
              return;
            }
            
            // Prepare the data in memory first to minimize time holding the lock
            byte[] fileData;
            try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
              ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
              channel.read(buffer);
              buffer.flip();
              fileData = new byte[buffer.limit()];
              buffer.get(fileData);
            }
            
            // Synchronize access to the ZipOutputStream
            synchronized (zipLock) {
              zos.putNextEntry(new ZipEntry(fileName));
              zos.write(fileData);
              zos.closeEntry();
            }
          } 
          catch (Exception e) {
            log.error("Error processing file: {}", fileName, e);
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
    }
    
    try {
      // Wait for all threads to complete
      latch.await();
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for zip tasks to complete", e);
    }
    
    // If any exceptions occurred, throw the first one
    if (!exceptions.isEmpty()) {
      Exception firstException = exceptions.get(0);
      if (firstException instanceof IOException) {
        throw (IOException) firstException;
      } 
      else {
        throw new IOException("Error during parallel zip operation", firstException);
      }
    }
  }
  
  /**
   * Unzips a ZIP file to the specified directory.
   *
   * @param zipFilePath Path to the ZIP file
   * @param destDir Directory where files will be extracted
   * @throws IOException If an I/O error occurs
   */
  public static void unzipFile(Path zipFilePath, Path destDir) throws IOException {
    unzipFile(zipFilePath, destDir, true);
  }
  
  /**
   * Unzips a ZIP file to the specified directory with option to use parallel processing.
   *
   * @param zipFilePath Path to the ZIP file
   * @param destDir Directory where files will be extracted
   * @param useParallel Whether to use parallel processing with Virtual Threads
   * @throws IOException If an I/O error occurs
   */
  public static void unzipFile(Path zipFilePath, Path destDir, boolean useParallel) throws IOException {
    if (!Files.exists(zipFilePath)) {
      throw new IOException("ZIP file does not exist: " + zipFilePath);
    }
    
    // Create destination directory if it doesn't exist
    Files.createDirectories(destDir);
    
    if (useParallel) {
      unzipFileParallel(zipFilePath, destDir);
    } else {
      unzipFileSequential(zipFilePath, destDir);
    }
  }
  
  /**
   * Unzips a file sequentially using improved buffer handling.
   *
   * @param zipFilePath Path to the ZIP file
   * @param destDir Directory where files will be extracted
   * @throws IOException If an I/O error occurs
   */
  /**
   * Unzips a file sequentially using improved buffer handling.
   *
   * @param zipFilePath Path to the ZIP file
   * @param destDir Directory where files will be extracted
   * @throws IOException If an I/O error occurs
   */
  private static void unzipFileSequential(Path zipFilePath, Path destDir) throws IOException {
    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
    
    try (InputStream fis = Files.newInputStream(zipFilePath);
         ZipInputStream zis = new ZipInputStream(fis)) {
      
      ZipEntry zipEntry;
      while ((zipEntry = zis.getNextEntry()) != null) {
        Path newPath = destDir.resolve(zipEntry.getName());
        
        // Create parent directories if they don't exist
        if (zipEntry.isDirectory()) {
          Files.createDirectories(newPath);
        } else {
          // Ensure parent directories exist for files too
          Files.createDirectories(newPath.getParent());
          
          // Extract file using NIO for better performance
          try (FileChannel fileChannel = FileChannel.open(newPath, 
              StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            
            ByteBuffer byteBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
            int bytesRead;
            while ((bytesRead = zis.read(buffer)) > 0) {
              byteBuffer.clear();
              byteBuffer.put(buffer, 0, bytesRead);
              byteBuffer.flip();
              fileChannel.write(byteBuffer);
            }
          }
        }
        zis.closeEntry();
      }
    }
  }
  
  /**
   * Unzips a file in parallel using Virtual Threads for improved performance.
   * First reads the ZIP entries, then extracts files in parallel using Virtual Threads.
   *
   * @param zipFilePath Path to the ZIP file
   * @param destDir Directory where files will be extracted
   * @throws IOException If an I/O error occurs
   */
  /**
   * Unzips a file in parallel using Virtual Threads for improved performance.
   * First reads the ZIP entries, then extracts files in parallel using Virtual Threads.
   *
   * @param zipFilePath Path to the ZIP file
   * @param destDir Directory where files will be extracted
   * @throws IOException If an I/O error occurs
   */
  private static void unzipFileParallel(Path zipFilePath, Path destDir) throws IOException {
    // First, read all entries and their data to avoid concurrent access to ZipInputStream
    List<ZipEntryData> entries = new ArrayList<>();
    
    try (InputStream fis = Files.newInputStream(zipFilePath);
         ZipInputStream zis = new ZipInputStream(fis)) {
      
      ZipEntry zipEntry;
      while ((zipEntry = zis.getNextEntry()) != null) {
        if (!zipEntry.isDirectory()) {
          // Read entry data into memory
          String entryName = zipEntry.getName();
          byte[] entryData = readAllBytes(zis);
          entries.add(new ZipEntryData(entryName, entryData));
        } else {
          // Create directory immediately
          Path dirPath = destDir.resolve(zipEntry.getName());
          Files.createDirectories(dirPath);
        }
        zis.closeEntry();
      }
    }
    
    if (entries.isEmpty()) {
      return; // No files to extract
    }
    
    // Track exceptions that occur in virtual threads
    final List<Exception> exceptions = new ArrayList<>();
    
    // Use CountDownLatch to wait for all threads to complete
    final CountDownLatch latch = new CountDownLatch(entries.size());
    
    // Extract files in parallel using Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (ZipEntryData entry : entries) {
        executor.submit(() -> {
          try {
            Path filePath = destDir.resolve(entry.name);
            
            // Ensure parent directories exist
            Files.createDirectories(filePath.getParent());
            
            // Write file data
            Files.write(filePath, entry.data);
          } 
          catch (Exception e) {
            log.error("Error extracting file: {}", entry.name, e);
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
    }
    
    try {
      // Wait for all threads to complete
      latch.await();
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for unzip tasks to complete", e);
    }
    
    // If any exceptions occurred, throw the first one
    if (!exceptions.isEmpty()) {
      Exception firstException = exceptions.get(0);
      if (firstException instanceof IOException) {
        throw (IOException) firstException;
      } 
      else {
        throw new IOException("Error during parallel unzip operation", firstException);
      }
    }
  }
  
  /**
   * Helper method to read all bytes from an input stream.
   * Similar to Files.readAllBytes but for InputStream.
   *
   * @param is The input stream to read from
   * @return Byte array containing all data from the stream
   * @throws IOException If an I/O error occurs
   */
  private static byte[] readAllBytes(InputStream is) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
    byte[] readBuffer = new byte[DEFAULT_BUFFER_SIZE];
    int bytesRead;
    
    while ((bytesRead = is.read(readBuffer)) != -1) {
      if (buffer.remaining() < bytesRead) {
        // Expand buffer if needed
        ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
        buffer.flip();
        newBuffer.put(buffer);
        buffer = newBuffer;
      }
      buffer.put(readBuffer, 0, bytesRead);
    }
    
    buffer.flip();
    byte[] result = new byte[buffer.limit()];
    buffer.get(result);
    return result;
  }
  
  /**
   * Helper class to store ZIP entry data for parallel extraction.
   */
  private static class ZipEntryData {
    final String name;
    final byte[] data;
    
    ZipEntryData(String name, byte[] data) {
      this.name = name;
      this.data = data;
    }
  }