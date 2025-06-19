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
package org.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Parameter;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit Jupiter extension for testing Java 21's record pattern matching features.
 * This extension provides:
 * <ul>
 *   <li>Conditional test execution based on Java version (requires Java 21+)</li>
 *   <li>Parameter resolution for record-based test data</li>
 *   <li>Utilities for generating nested record structures</li>
 *   <li>Verification utilities for record pattern matching</li>
 * </ul>
 * 
 * <p>Usage example:</p>
 * <pre>
 * {@code
 * @ExtendWith(RecordPatternExtension.class)
 * class RecordPatternTest {
 *   @Test
 *   void testNestedRecordPatterns(NestedTestRecord testData) {
 *     // Test code using record patterns
 *   }
 * }
 * }
 * </pre>
 */
/**
 * JUnit Jupiter extension for testing Java 21's record pattern matching features.
 * This extension enables tests to validate code that uses record patterns for data extraction
 * and nested pattern matching, a powerful Java 21 language feature.
 */
public class RecordPatternExtension implements ExecutionCondition, ParameterResolver {

    private static final String RECORD_PATTERN_ENABLED_PROPERTY = "nexus.test.recordpattern.enabled";
    private static final String JAVA_VERSION_PROPERTY = "java.version";
    private static final int REQUIRED_JAVA_VERSION = 21;

    /**
     * Annotation to mark parameters that should be resolved with test data for record patterns.
     */
    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RecordPatternTest {
        /**
         * Specifies the nesting level of the test data.
         * Higher values create more deeply nested record structures.
         */
        int nestingLevel() default 1;
        
        /**
         * Specifies the type of test data to generate.
         */
        TestDataType type() default TestDataType.SIMPLE;
    }
    
    /**
     * Enum defining different types of test data for record patterns.
     */
    public enum TestDataType {
        /** Simple record with basic fields */
        SIMPLE,
        /** Nested records for testing deep pattern matching */
        NESTED,
        /** Records with collections for testing pattern matching with collections */
        COLLECTION,
        /** Records with optional fields for testing pattern matching with optionals */
        OPTIONAL
    }
    
    /**
     * Simple record for testing basic pattern matching.
     * @param name A string value
     * @param value An integer value
     */
    public record SimpleTestRecord(String name, int value) {}
    
    /**
     * Nested record for testing nested pattern matching.
     * @param id An identifier
     * @param data The nested data
     */
    public record NestedTestRecord(String id, SimpleTestRecord data) {}
    
    /**
     * Deeply nested record for testing complex pattern matching.
     * @param id An identifier
     * @param nested The nested record
     */
    public record DeepNestedTestRecord(String id, NestedTestRecord nested) {}
    
    /**
     * Record with an optional field for testing pattern matching with optionals.
     * @param id An identifier
     * @param data Optional data
     */
    public record OptionalTestRecord(String id, Optional<SimpleTestRecord> data) {}
    
    /**
     * Record with a collection for testing pattern matching with collections.
     * @param id An identifier
     * @param items Collection of simple records
     */
    public record CollectionTestRecord(String id, java.util.List<SimpleTestRecord> items) {}

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // Check if Java version is 21 or higher
        if (!isJava21OrHigher()) {
            return ConditionEvaluationResult.disabled(
                    "Record pattern tests require Java 21 or higher, current version: " + 
                    System.getProperty(JAVA_VERSION_PROPERTY));
        }
        
        // Check if tests are explicitly disabled via system property
        String recordPatternEnabled = System.getProperty(RECORD_PATTERN_ENABLED_PROPERTY);
        if (recordPatternEnabled != null && recordPatternEnabled.equalsIgnoreCase("false")) {
            return ConditionEvaluationResult.disabled(
                    "Record pattern tests disabled via system property: " + 
                    RECORD_PATTERN_ENABLED_PROPERTY);
        }
        
        return ConditionEvaluationResult.enabled(
                "Record pattern tests enabled on Java " + System.getProperty(JAVA_VERSION_PROPERTY));
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Parameter parameter = parameterContext.getParameter();
        Class<?> type = parameter.getType();
        
        // Support our defined record types
        return type.equals(SimpleTestRecord.class) ||
               type.equals(NestedTestRecord.class) ||
               type.equals(DeepNestedTestRecord.class) ||
               type.equals(OptionalTestRecord.class) ||
               type.equals(CollectionTestRecord.class) ||
               type.equals(Shape.class) ||
               type.equals(Circle.class) ||
               type.equals(Rectangle.class) ||
               type.equals(TaskConfig.class) ||
               type.equals(TaskResult.class) ||
               type.equals(BlobMetadata.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Parameter parameter = parameterContext.getParameter();
        Class<?> type = parameter.getType();
        
        // Get nesting level and test data type from annotation if present
        int nestingLevel = 1;
        TestDataType dataType = TestDataType.SIMPLE;
        
        if (parameter.isAnnotationPresent(RecordPatternTest.class)) {
            RecordPatternTest annotation = parameter.getAnnotation(RecordPatternTest.class);
            nestingLevel = annotation.nestingLevel();
            dataType = annotation.type();
        }
        
        // Generate appropriate test data based on parameter type and annotation
        if (type.equals(SimpleTestRecord.class)) {
            return new SimpleTestRecord("test", 42);
        } 
        else if (type.equals(NestedTestRecord.class)) {
            return new NestedTestRecord("nested", new SimpleTestRecord("inner", 100));
        }
        else if (type.equals(DeepNestedTestRecord.class)) {
            return new DeepNestedTestRecord("deep", 
                    new NestedTestRecord("middle", new SimpleTestRecord("innermost", 200)));
        }
        else if (type.equals(OptionalTestRecord.class)) {
            return new OptionalTestRecord("optional", 
                    Optional.of(new SimpleTestRecord("optional-data", 300)));
        }
        else if (type.equals(CollectionTestRecord.class)) {
            return new CollectionTestRecord("collection", 
                    java.util.List.of(
                            new SimpleTestRecord("item1", 1),
                            new SimpleTestRecord("item2", 2),
                            new SimpleTestRecord("item3", 3)
                    ));
        }
        else if (type.equals(Circle.class)) {
            return new Circle(5.0, new Point(0.0, 0.0));
        }
        else if (type.equals(Rectangle.class)) {
            return new Rectangle(10.0, 5.0, new Point(0.0, 0.0));
        }
        else if (type.equals(Shape.class)) {
            // Randomly return either a Circle or Rectangle
            return Math.random() > 0.5 ?
                    new Circle(5.0, new Point(0.0, 0.0)) :
                    new Rectangle(10.0, 5.0, new Point(0.0, 0.0));
        }
        else if (type.equals(TaskConfig.class)) {
            return createTaskConfig("test-task", "file", "/path/to/storage", 1024 * 1024 * 100, true);
        }
        else if (type.equals(TaskResult.class)) {
            return createTaskResult("task-123", Status.SUCCESS, 100, 0, "Completed successfully");
        }
        else if (type.equals(BlobMetadata.class)) {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Disposition", "attachment; filename=test.txt");
            headers.put("X-Test-Header", "test-value");
            return createBlobMetadata("blob-123", "text/plain", 1024, headers);
        }
        
        throw new ParameterResolutionException("Unsupported parameter type: " + type.getName());
    }
    
    /**
     * Checks if the current Java version is 21 or higher.
     * 
     * @return true if running on Java 21+, false otherwise
     */
    private boolean isJava21OrHigher() {
        String version = System.getProperty(JAVA_VERSION_PROPERTY, "");
        try {
            // Extract major version number
            if (version.startsWith("1.")) {
                // Old version format (1.8, etc.)
                version = version.substring(2, 3);
            } else {
                // New version format (9, 10, 11, etc.)
                int dotIndex = version.indexOf('.');
                if (dotIndex != -1) {
                    version = version.substring(0, dotIndex);
                }
            }
            
            int majorVersion = Integer.parseInt(version);
            return majorVersion >= REQUIRED_JAVA_VERSION;
        } catch (NumberFormatException e) {
            // If we can't parse the version, assume it's not compatible
            return false;
        }
    }
    
    /**
     * Utility method to create a simple test record.
     * 
     * @param name The name field
     * @param value The value field
     * @return A new SimpleTestRecord
     */
    public static SimpleTestRecord createSimpleRecord(String name, int value) {
        return new SimpleTestRecord(name, value);
    }
    
    /**
     * Utility method to create a nested test record.
     * 
     * @param id The id field
     * @param innerName The name for the inner record
     * @param innerValue The value for the inner record
     * @return A new NestedTestRecord
     */
    public static NestedTestRecord createNestedRecord(String id, String innerName, int innerValue) {
        return new NestedTestRecord(id, new SimpleTestRecord(innerName, innerValue));
    }
    
    /**
     * Utility method to create a deeply nested test record.
     * 
     * @param id The outer id
     * @param middleId The middle id
     * @param innerName The innermost name
     * @param innerValue The innermost value
     * @return A new DeepNestedTestRecord
     */
    public static DeepNestedTestRecord createDeepNestedRecord(
            String id, String middleId, String innerName, int innerValue) {
        return new DeepNestedTestRecord(id, 
                new NestedTestRecord(middleId, new SimpleTestRecord(innerName, innerValue)));
    }
    
    /**
     * Utility method to verify record pattern extraction works correctly.
     * This method demonstrates how to use record patterns to extract data.
     * 
     * @param record The record to extract data from
     * @return The extracted value, or -1 if extraction failed
     */
    public static int extractValueUsingPatterns(Object record) {
        // In Java 21, this would use actual record patterns:
        // if (record instanceof SimpleTestRecord(String name, int value)) {
        //     return value;
        // } else if (record instanceof NestedTestRecord(String id, SimpleTestRecord(String name, int value))) {
        //     return value;
        // } ...
        
        // For now, we use traditional instanceof and casting
        if (record instanceof SimpleTestRecord) {
            SimpleTestRecord simple = (SimpleTestRecord) record;
            return simple.value();
        } else if (record instanceof NestedTestRecord) {
            NestedTestRecord nested = (NestedTestRecord) record;
            return nested.data().value();
        } else if (record instanceof DeepNestedTestRecord) {
            DeepNestedTestRecord deep = (DeepNestedTestRecord) record;
            return deep.nested().data().value();
        } else if (record instanceof OptionalTestRecord) {
            OptionalTestRecord optional = (OptionalTestRecord) record;
            return optional.data().map(SimpleTestRecord::value).orElse(-1);
        } else if (record instanceof CollectionTestRecord) {
            CollectionTestRecord collection = (CollectionTestRecord) record;
            return collection.items().stream()
                    .mapToInt(SimpleTestRecord::value)
                    .sum();
        }
        return -1;
    }
    
    /**
     * Utility method to demonstrate how record patterns would be used in switch expressions.
     * In Java 21, this would use pattern matching in switch.
     * 
     * @param record The record to extract data from using switch pattern matching
     * @return A string representation of the extracted data
     */
    public static String extractUsingPatternSwitch(Object record) {
        // In Java 21, this would use pattern matching in switch:
        // return switch (record) {
        //     case SimpleTestRecord(String name, int value) -> name + ": " + value;
        //     case NestedTestRecord(String id, SimpleTestRecord(String name, int value)) -> 
        //         id + " -> " + name + ": " + value;
        //     case DeepNestedTestRecord(String id, NestedTestRecord(String mid, SimpleTestRecord(String name, int value))) ->
        //         id + " -> " + mid + " -> " + name + ": " + value;
        //     default -> "Unknown record type";
        // };
        
        // For now, we use traditional instanceof and casting
        if (record instanceof SimpleTestRecord) {
            SimpleTestRecord simple = (SimpleTestRecord) record;
            return simple.name() + ": " + simple.value();
        } else if (record instanceof NestedTestRecord) {
            NestedTestRecord nested = (NestedTestRecord) record;
            SimpleTestRecord inner = nested.data();
            return nested.id() + " -> " + inner.name() + ": " + inner.value();
        } else if (record instanceof DeepNestedTestRecord) {
            DeepNestedTestRecord deep = (DeepNestedTestRecord) record;
            NestedTestRecord middle = deep.nested();
            SimpleTestRecord inner = middle.data();
            return deep.id() + " -> " + middle.id() + " -> " + inner.name() + ": " + inner.value();
        } else if (record instanceof OptionalTestRecord) {
            OptionalTestRecord optional = (OptionalTestRecord) record;
            return optional.id() + " -> " + 
                   optional.data().map(d -> d.name() + ": " + d.value()).orElse("empty");
        } else if (record instanceof CollectionTestRecord) {
            CollectionTestRecord collection = (CollectionTestRecord) record;
            return collection.id() + " -> [" + 
                   collection.items().stream()
                       .map(item -> item.name() + ": " + item.value())
                       .reduce((a, b) -> a + ", " + b)
                       .orElse("") + "]";
        }
        return "Unknown record type";
    }
    
    /**
     * Utility method to demonstrate how record patterns with guards would be used.
     * In Java 21, this would use pattern matching with guards.
     * 
     * @param record The record to check against patterns with guards
     * @return true if the record matches specific criteria, false otherwise
     */
    public static boolean matchesPatternWithGuard(Object record) {
        // In Java 21, this would use pattern matching with guards:
        // if (record instanceof SimpleTestRecord(String name, int value) && value > 40) {
        //     return true;
        // } else if (record instanceof NestedTestRecord(String id, SimpleTestRecord(String name, int value)) 
        //            && id.startsWith("nested") && value > 50) {
        //     return true;
        // } ...
        
        // For now, we use traditional instanceof and casting with guards
        if (record instanceof SimpleTestRecord) {
            SimpleTestRecord simple = (SimpleTestRecord) record;
            return simple.value() > 40;
        } else if (record instanceof NestedTestRecord) {
            NestedTestRecord nested = (NestedTestRecord) record;
            return nested.id().startsWith("nested") && nested.data().value() > 50;
        } else if (record instanceof DeepNestedTestRecord) {
            DeepNestedTestRecord deep = (DeepNestedTestRecord) record;
            return deep.id().startsWith("deep") && 
                   deep.nested().id().startsWith("middle") && 
                   deep.nested().data().value() > 100;
        }
        return false;
    }
    
    /**
     * Record for testing pattern matching with sealed interfaces.
     * In Java 21, sealed interfaces work well with pattern matching.
     */
    public sealed interface Shape permits Circle, Rectangle {}
    
    /**
     * Circle implementation of the Shape interface.
     */
    public record Circle(double radius, Point center) implements Shape {}
    
    /**
     * Rectangle implementation of the Shape interface.
     */
    public record Rectangle(double width, double height, Point topLeft) implements Shape {}
    
    /**
     * Point record for use with shapes.
     */
    public record Point(double x, double y) {}
    
    /**
     * Utility method to demonstrate how pattern matching works with sealed interfaces.
     * In Java 21, this would use pattern matching with sealed types.
     * 
     * @param shape The shape to calculate area for
     * @return The area of the shape
     */
    public static double calculateArea(Shape shape) {
        // In Java 21, this would use pattern matching with sealed types:
        // return switch (shape) {
        //     case Circle(double radius, Point center) -> Math.PI * radius * radius;
        //     case Rectangle(double width, double height, Point topLeft) -> width * height;
        // };
        
        // For now, we use traditional instanceof and casting
        if (shape instanceof Circle) {
            Circle circle = (Circle) shape;
            return Math.PI * circle.radius() * circle.radius();
        } else if (shape instanceof Rectangle) {
            Rectangle rectangle = (Rectangle) shape;
            return rectangle.width() * rectangle.height();
        }
        throw new IllegalArgumentException("Unknown shape type");
    }
    
    /**
     * Creates a Circle shape for testing pattern matching with sealed interfaces.
     * 
     * @param radius The radius of the circle
     * @param x The x-coordinate of the center
     * @param y The y-coordinate of the center
     * @return A new Circle instance
     */
    public static Circle createCircle(double radius, double x, double y) {
        return new Circle(radius, new Point(x, y));
    }
    
    /**
     * Creates a Rectangle shape for testing pattern matching with sealed interfaces.
     * 
     * @param width The width of the rectangle
     * @param height The height of the rectangle
     * @param x The x-coordinate of the top-left corner
     * @param y The y-coordinate of the top-left corner
     * @return A new Rectangle instance
     */
    public static Rectangle createRectangle(double width, double height, double x, double y) {
        return new Rectangle(width, height, new Point(x, y));
    }
    
    /**
     * Record for representing a task configuration with nested data.
     * Useful for testing pattern matching with blobstore task configurations.
     */
    public record TaskConfig(String name, ConfigProperties properties) {}
    
    /**
     * Record for representing configuration properties.
     */
    public record ConfigProperties(String blobstoreType, StorageOptions storage) {}
    
    /**
     * Record for representing storage options.
     */
    public record StorageOptions(String path, long quota, boolean compressed) {}
    
    /**
     * Creates a task configuration for testing pattern matching with blobstore tasks.
     * 
     * @param name The task name
     * @param blobstoreType The blobstore type
     * @param path The storage path
     * @param quota The storage quota
     * @param compressed Whether storage is compressed
     * @return A new TaskConfig instance
     */
    public static TaskConfig createTaskConfig(String name, String blobstoreType, String path, long quota, boolean compressed) {
        return new TaskConfig(name, new ConfigProperties(blobstoreType, new StorageOptions(path, quota, compressed)));
    }
    
    /**
     * Utility method to demonstrate how pattern matching would be used to extract and validate
     * task configuration properties.
     * 
     * @param config The task configuration to validate
     * @return true if the configuration is valid, false otherwise
     */
    public static boolean validateTaskConfig(Object config) {
        // In Java 21, this would use pattern matching:
        // if (config instanceof TaskConfig(String name, ConfigProperties(String type, StorageOptions(String path, long quota, boolean compressed)))) {
        //     return !name.isEmpty() && ("file".equals(type) || "s3".equals(type)) && 
        //            !path.isEmpty() && quota > 0;
        // }
        
        // For now, we use traditional instanceof and casting
        if (config instanceof TaskConfig) {
            TaskConfig taskConfig = (TaskConfig) config;
            String name = taskConfig.name();
            ConfigProperties props = taskConfig.properties();
            String type = props.blobstoreType();
            StorageOptions storage = props.storage();
            String path = storage.path();
            long quota = storage.quota();
            
            return !name.isEmpty() && ("file".equals(type) || "s3".equals(type)) && 
                   !path.isEmpty() && quota > 0;
        }
        return false;
    }
    
    /**
     * Record for representing a task result with nested data.
     * Useful for testing pattern matching with blobstore task results.
     */
    public record TaskResult(String taskId, Status status, ResultData data) {}
    
    /**
     * Status enum for task results.
     */
    public enum Status { SUCCESS, FAILURE, PARTIAL }
    
    /**
     * Record for representing task result data.
     */
    public record ResultData(long processedCount, long errorCount, java.util.List<String> messages) {}
    
    /**
     * Creates a task result for testing pattern matching with blobstore tasks.
     * 
     * @param taskId The task ID
     * @param status The task status
     * @param processedCount The number of items processed
     * @param errorCount The number of errors
     * @param messages The result messages
     * @return A new TaskResult instance
     */
    public static TaskResult createTaskResult(String taskId, Status status, long processedCount, long errorCount, String... messages) {
        return new TaskResult(taskId, status, new ResultData(processedCount, errorCount, java.util.List.of(messages)));
    }
    
    /**
     * Utility method to demonstrate how pattern matching would be used to process task results.
     * 
     * @param result The task result to process
     * @return A summary of the task result
     */
    public static String processTaskResult(Object result) {
        // In Java 21, this would use pattern matching in switch:
        // return switch (result) {
        //     case TaskResult(String id, Status.SUCCESS, ResultData(long processed, long errors, var msgs)) when errors == 0 ->
        //         "Task " + id + " completed successfully, processed " + processed + " items";
        //     case TaskResult(String id, Status.SUCCESS, ResultData(long processed, long errors, var msgs)) ->
        //         "Task " + id + " completed with " + errors + " errors, processed " + processed + " items";
        //     case TaskResult(String id, Status.FAILURE, ResultData(long processed, long errors, var msgs)) ->
        //         "Task " + id + " failed with " + errors + " errors, processed " + processed + " items";
        //     case TaskResult(String id, Status.PARTIAL, ResultData(long processed, long errors, var msgs)) ->
        //         "Task " + id + " partially completed with " + errors + " errors, processed " + processed + " items";
        //     default -> "Unknown result";
        // };
        
        // For now, we use traditional instanceof and casting
        if (result instanceof TaskResult) {
            TaskResult taskResult = (TaskResult) result;
            String id = taskResult.taskId();
            Status status = taskResult.status();
            ResultData data = taskResult.data();
            long processed = data.processedCount();
            long errors = data.errorCount();
            
            if (status == Status.SUCCESS && errors == 0) {
                return "Task " + id + " completed successfully, processed " + processed + " items";
            } else if (status == Status.SUCCESS) {
                return "Task " + id + " completed with " + errors + " errors, processed " + processed + " items";
            } else if (status == Status.FAILURE) {
                return "Task " + id + " failed with " + errors + " errors, processed " + processed + " items";
            } else if (status == Status.PARTIAL) {
                return "Task " + id + " partially completed with " + errors + " errors, processed " + processed + " items";
            }
        }
        return "Unknown result";
    }
    
    /**
     * Record for representing a blob metadata entry.
     * Useful for testing pattern matching with blobstore operations.
     */
    public record BlobMetadata(String blobId, BlobAttributes attributes, BlobContent content) {}
    
    /**
     * Record for representing blob attributes.
     */
    public record BlobAttributes(String contentType, long size, java.time.OffsetDateTime created, 
                                java.util.Map<String, String> headers) {}
    
    /**
     * Record for representing blob content.
     */
    public record BlobContent(byte[] data, String sha1, String md5) {}
    
    /**
     * Creates a blob metadata entry for testing pattern matching with blobstore operations.
     * 
     * @param blobId The blob ID
     * @param contentType The content type
     * @param size The blob size
     * @param headers The blob headers
     * @return A new BlobMetadata instance
     */
    public static BlobMetadata createBlobMetadata(String blobId, String contentType, long size, 
                                                 java.util.Map<String, String> headers) {
        return new BlobMetadata(
                blobId,
                new BlobAttributes(
                        contentType,
                        size,
                        java.time.OffsetDateTime.now(),
                        headers
                ),
                new BlobContent(
                        new byte[0], // Empty data for testing
                        "sha1hash",
                        "md5hash"
                )
        );
    }
    
    /**
     * Utility method to demonstrate how pattern matching would be used to extract and validate
     * blob metadata.
     * 
     * @param metadata The blob metadata to validate
     * @return true if the metadata is valid, false otherwise
     */
    public static boolean validateBlobMetadata(Object metadata) {
        // In Java 21, this would use pattern matching:
        // if (metadata instanceof BlobMetadata(String id, BlobAttributes(String contentType, long size, var created, var headers), var content)) {
        //     return !id.isEmpty() && !contentType.isEmpty() && size > 0 && created != null;
        // }
        
        // For now, we use traditional instanceof and casting
        if (metadata instanceof BlobMetadata) {
            BlobMetadata blobMetadata = (BlobMetadata) metadata;
            String id = blobMetadata.blobId();
            BlobAttributes attrs = blobMetadata.attributes();
            String contentType = attrs.contentType();
            long size = attrs.size();
            java.time.OffsetDateTime created = attrs.created();
            
            return !id.isEmpty() && !contentType.isEmpty() && size > 0 && created != null;
        }
        return false;
    }
    
    /**
     * Utility method to demonstrate how pattern matching would be used to extract content type
     * from blob metadata.
     * 
     * @param metadata The blob metadata to extract content type from
     * @return The content type, or "unknown" if not available
     */
    public static String extractContentType(Object metadata) {
        // In Java 21, this would use pattern matching:
        // if (metadata instanceof BlobMetadata(var id, BlobAttributes(String contentType, var size, var created, var headers), var content)) {
        //     return contentType;
        // }
        
        // For now, we use traditional instanceof and casting
        if (metadata instanceof BlobMetadata) {
            BlobMetadata blobMetadata = (BlobMetadata) metadata;
            return blobMetadata.attributes().contentType();
        }
        return "unknown";
    }
    
    /**
     * Utility method to demonstrate how pattern matching would be used to extract headers
     * from blob metadata.
     * 
     * @param metadata The blob metadata to extract headers from
     * @return The headers, or an empty map if not available
     */
    public static java.util.Map<String, String> extractHeaders(Object metadata) {
        // In Java 21, this would use pattern matching:
        // if (metadata instanceof BlobMetadata(var id, BlobAttributes(var contentType, var size, var created, java.util.Map<String, String> headers), var content)) {
        //     return headers;
        // }
        
        // For now, we use traditional instanceof and casting
        if (metadata instanceof BlobMetadata) {
            BlobMetadata blobMetadata = (BlobMetadata) metadata;
            return blobMetadata.attributes().headers();
        }
        return java.util.Collections.emptyMap();
    }
    
    /**
     * Example of how to use this extension in a test class.
     * <pre>
     * {@code
     * @ExtendWith(RecordPatternExtension.class)
     * class RecordPatternTest {
     *   @Test
     *   void testSimpleRecordPattern(SimpleTestRecord record) {
     *     // In Java 21, you would use record patterns:
     *     // if (record instanceof SimpleTestRecord(String name, int value)) {
     *     //   assertEquals("test", name);
     *     //   assertEquals(42, value);
     *     // }
     *     
     *     // For now, use traditional accessors:
     *     assertEquals("test", record.name());
     *     assertEquals(42, record.value());
     *   }
     *   
     *   @Test
     *   void testNestedRecordPattern(NestedTestRecord record) {
     *     // In Java 21, you would use nested record patterns:
     *     // if (record instanceof NestedTestRecord(String id, SimpleTestRecord(String name, int value))) {
     *     //   assertEquals("nested", id);
     *     //   assertEquals("inner", name);
     *     //   assertEquals(100, value);
     *     // }
     *     
     *     // For now, use traditional accessors:
     *     assertEquals("nested", record.id());
     *     assertEquals("inner", record.data().name());
     *     assertEquals(100, record.data().value());
     *   }
     *   
     *   @Test
     *   void testTaskConfigValidation() {
     *     TaskConfig config = RecordPatternExtension.createTaskConfig(
     *         "backup-task", "file", "/data/backup", 1024 * 1024 * 500, true);
     *     
     *     assertTrue(RecordPatternExtension.validateTaskConfig(config));
     *   }
     *   
     *   @Test
     *   void testShapePatternMatching(Shape shape) {
     *     double area = RecordPatternExtension.calculateArea(shape);
     *     
     *     // In Java 21, you would use pattern matching in assertions:
     *     // if (shape instanceof Circle(double radius, Point center)) {
     *     //   assertEquals(Math.PI * radius * radius, area, 0.001);
     *     // } else if (shape instanceof Rectangle(double width, double height, Point topLeft)) {
     *     //   assertEquals(width * height, area, 0.001);
     *     // }
     *     
     *     // For now, use traditional instanceof and casting:
     *     if (shape instanceof Circle) {
     *       Circle circle = (Circle) shape;
     *       assertEquals(Math.PI * circle.radius() * circle.radius(), area, 0.001);
     *     } else if (shape instanceof Rectangle) {
     *       Rectangle rectangle = (Rectangle) shape;
     *       assertEquals(rectangle.width() * rectangle.height(), area, 0.001);
     *     }
     *   }
     * }
     * }
     * </pre>
     */
    public static class ExampleUsage {}
}