package io.ariadne.core;

import java.util.Objects;

/**
 * Metadata associated with an asynchronous dispatch call site.
 */
public final class CallSiteMetadata {

    private final String className;
    private final String methodName;
    private final String fileName;
    private final int lineNumber;
    private final String description;

    public CallSiteMetadata(String className, String methodName, String fileName, int lineNumber, String description) {
        this.className = className != null ? className : "unknown";
        this.methodName = methodName != null ? methodName : "unknown";
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.description = description;
    }

    public CallSiteMetadata(String className, String methodName, String fileName, int lineNumber) {
        this(className, methodName, fileName, lineNumber, null);
    }

    public String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public String fileName() {
        return fileName;
    }

    public int lineNumber() {
        return lineNumber;
    }

    public String description() {
        return description;
    }

    /**
     * Converts this metadata into a standard JVM StackTraceElement.
     */
    public StackTraceElement toStackTraceElement() {
        return new StackTraceElement(
                className,
                methodName,
                fileName,
                lineNumber
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CallSiteMetadata that)) return false;
        return lineNumber == that.lineNumber &&
                Objects.equals(className, that.className) &&
                Objects.equals(methodName, that.methodName) &&
                Objects.equals(fileName, that.fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, fileName, lineNumber);
    }

    @Override
    public String toString() {
        return className + "." + methodName +
                (fileName != null ? "(" + fileName + ":" + lineNumber + ")" : "") +
                (description != null ? " [" + description + "]" : "");
    }
}
